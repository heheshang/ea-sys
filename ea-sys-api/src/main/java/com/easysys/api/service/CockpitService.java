package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.agent.AgentOutcome;
import com.easysys.agent.AgentPolicy;
import com.easysys.agent.AgentRunConfig;
import com.easysys.agent.AgentType;
import com.easysys.agent.CockpitInsightModel;
import com.easysys.api.AgentCatalogRegistry;
import com.easysys.api.config.AgentLlmProperties;
import com.easysys.api.dto.cockpit.AgentGraphEntryView;
import com.easysys.api.dto.cockpit.CockpitInsightView;
import com.easysys.api.dto.cockpit.CockpitOverviewView;
import com.easysys.api.dto.cockpit.LlmTraceView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.AgentGraphEntry;
import com.easysys.api.entity.EvaluationDataset;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.entity.KbDocument;
import com.easysys.api.entity.KbDocumentChunk;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.AgentGraphEntryMapper;
import com.easysys.api.mapper.EvaluationDatasetMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
import com.easysys.api.mapper.KbDocumentChunkMapper;
import com.easysys.api.mapper.KbDocumentMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.api.middleware.LlmContextEstimator;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 驾驶舱：图谱管理（内置目录 ∪ 用户登记，状态管理）+ 监控总览（LLM 调用聚合/图谱/知识库/记忆）
 * + 洞察（AgentPolicy 确定性规划 + 300s 缓存）+ LLM 调用追踪。
 *
 * <p>洞察与 LLM 聚合完全走 audit_log（追加写审计），不新增调用埋点；
 * 图谱 CRUD 为纯登记操作不触发 Agent。</p>
 */
@Service
public class CockpitService {

    private static final String INSIGHT_CACHE_PREFIX = "easysys:cockpit:insights:";
    private static final long INSIGHT_CACHE_TTL_SECONDS = 300L;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final AgentGraphEntryMapper graphMapper;
    private final AgentAuditMapper auditMapper;
    private final LlmUsageService llmUsageService;
    private final HarnessAgent assistantAgent;
    private final HarnessAgent workflowDialogueAgent;
    private final KbDocumentMapper kbDocumentMapper;
    private final KbDocumentChunkMapper kbChunkMapper;
    private final EvaluationDatasetMapper datasetMapper;
    private final EvaluationReportMapper reportMapper;
    private final HarnessAgent cockpitInsightAgent;
    private final AgentLlmProperties llm;
    private final JedisPooled jedis;
    private final RedissonClient redisson;
    private final ObjectMapper json;

    public CockpitService(AgentGraphEntryMapper graphMapper, AgentAuditMapper auditMapper,
                          KbDocumentMapper kbDocumentMapper, KbDocumentChunkMapper kbChunkMapper,
                          EvaluationDatasetMapper datasetMapper, EvaluationReportMapper reportMapper,
                          HarnessAgent cockpitInsightAgent, AgentLlmProperties llm, LlmUsageService llmUsageService,
                          @Qualifier("assistantAgent") HarnessAgent assistantAgent,
                          @Qualifier("workflowDialogueAgent") HarnessAgent workflowDialogueAgent,
                          JedisPooled agentscopeJedisPooled, RedissonClient redisson, ObjectMapper json) {
        this.graphMapper = graphMapper;
        this.auditMapper = auditMapper;
        this.llmUsageService = llmUsageService;
        this.assistantAgent = assistantAgent;
        this.workflowDialogueAgent = workflowDialogueAgent;
        this.kbDocumentMapper = kbDocumentMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.datasetMapper = datasetMapper;
        this.reportMapper = reportMapper;
        this.cockpitInsightAgent = cockpitInsightAgent;
        this.llm = llm;
        this.jedis = agentscopeJedisPooled;
        this.redisson = redisson;
        this.json = json;
    }

    // ---------- 监控总览 ----------

    /** 总览：LLM 聚合 + 图谱 + 知识库 + 记忆 + Agent 目录。 */
    public CockpitOverviewView overview() {
        Long tenantId = TenantContext.require();
        LlmAgg agg = llmAggregate(tenantId);

        List<CockpitOverviewView.LlmSeries> byAgent = agg.rows.stream()
                .map(r -> new CockpitOverviewView.LlmSeries(
                        str(r, "agent_type"), lng(r, "calls"), lng(r, "success"), lng(r, "fallback"),
                        lng(r, "error"), dbl(r, "avg_duration_ms"), lng(r, "sum_tokens"),
                        scale(bd(r, "sum_cost"))))
                .toList();
        List<CockpitOverviewView.LlmSeries> byModel = agg.modelRows.stream()
                .map(r -> new CockpitOverviewView.LlmSeries(
                        str(r, "model"), lng(r, "calls"), lng(r, "success"), lng(r, "fallback"),
                        lng(r, "error"), dbl(r, "avg_duration_ms"), lng(r, "sum_tokens"),
                        scale(bd(r, "sum_cost"))))
                .toList();
        List<CockpitOverviewView.LlmTrend> trend = agg.trendRows.stream()
                .map(r -> new CockpitOverviewView.LlmTrend(
                        str(r, "day"), lng(r, "calls"), lng(r, "success"),
                        lng(r, "sum_tokens"), scale(bd(r, "sum_cost"))))
                .toList();

        // 调用 = audit 批处理 + 聊天通道 llm_usage（防双计）；总 Token = llm_usage 输入+输出权威全量
        // （含超时但服务端已消耗的调用与未来聊天 LLM —— 与输入/输出/缓存命中三卡同源自洽；
        //   分组表按 audit 视角，正常无超时时两者相等，异常时段总卡 ≥ 分组和是真实消耗差）。
        CockpitOverviewView.LlmOverview llmView = new CockpitOverviewView.LlmOverview(
                llm.isEnabled(), llm.getModelId(), agg.calls + agg.chatCalls, agg.success, agg.fallback, agg.error,
                agg.avgDurationMs, agg.usageTokens, scale(agg.sumCost),
                round4(agg.schemaValidRate), round4(agg.errorRate), round4(agg.fallbackRate),
                agg.rounds, agg.sumInputTokens, agg.sumOutputTokens, agg.sumCachedTokens,
                lastChatContext(tenantId),
                byAgent, byModel, trend);

        // 图谱：内置目录 ∪ 用户登记按模块统计（与 listGraph 同源口径）
        Map<String, long[]> perModule = new LinkedHashMap<>();
        for (String m : AgentCatalogRegistry.MODULES) {
            perModule.put(m, new long[]{0, 0});
        }
        long total = 0;
        long enabled = 0;
        for (AgentCatalogRegistry.Item it : AgentCatalogRegistry.all()) {
            long[] c = perModule.get(it.module());
            c[0]++;
            if ("ENABLED".equals(it.status())) {
                c[1]++;
            }
            total++;
            if ("ENABLED".equals(it.status())) {
                enabled++;
            }
        }
        Map<String, AgentGraphEntry> userRows = userEntries(tenantId);
        for (AgentGraphEntry e : userRows.values()) {
            long[] c = perModule.get(e.getModule());
            if (c == null) {
                continue;
            }
            boolean wasBuiltin = BUILTIN_KEYS.contains(e.getModule() + ":" + e.getEntryKey());
            if (wasBuiltin) {
                c[0]--; // 覆盖内置项，不重复计数
                if ("ENABLED".equals(e.getStatus())) {
                    c[1]--;
                }
            }
            c[0]++;
            if ("ENABLED".equals(e.getStatus())) {
                c[1]++;
            }
        }
        total = 0;
        enabled = 0;
        List<CockpitOverviewView.ModuleStat> modules = new ArrayList<>();
        for (String m : AgentCatalogRegistry.MODULES) {
            long[] c = perModule.get(m);
            modules.add(new CockpitOverviewView.ModuleStat(m, c[0], c[1]));
            total += c[0];
            enabled += c[1];
        }

        Long tenantIdFinal = tenantId;
        long docs = kbDocumentMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getTenantId, tenantIdFinal));
        long chunks = kbChunkMapper.selectCount(new LambdaQueryWrapper<KbDocumentChunk>()
                .eq(KbDocumentChunk::getTenantId, tenantIdFinal));

        List<CockpitOverviewView.AgentStat> agentStats = new ArrayList<>();
        for (AgentType t : AgentType.values()) {
            agentStats.add(new CockpitOverviewView.AgentStat(t.name(), agentLabel(t), llm.isEnabled(), llm.getModelId()));
        }

        return new CockpitOverviewView(llmView,
                new CockpitOverviewView.GraphOverview(total, enabled, modules),
                new CockpitOverviewView.KnowledgeOverview(docs, chunks),
                new CockpitOverviewView.MemoryOverview(memoryKeys()),
                new CockpitOverviewView.AgentsOverview(agentStats));
    }

    // ---------- 图谱管理 ----------

    /** 图谱清单：module 过滤（空 = 全部）；内置目录 ∪ 用户登记，同 (module, entry_key) 用户行覆盖内置。 */
    public List<AgentGraphEntryView> listGraph(String module) {
        Long tenantId = TenantContext.require();
        Map<String, AgentGraphEntry> users = userEntries(tenantId);
        List<AgentGraphEntryView> out = new ArrayList<>();
        for (AgentCatalogRegistry.Item it : AgentCatalogRegistry.all()) {
            if (module != null && !module.isBlank() && !module.equals(it.module())) {
                continue;
            }
            AgentGraphEntry user = users.get(it.module() + ":" + it.entryKey());
            if (user != null) {
                out.add(toView(user, "user"));
            } else {
                out.add(new AgentGraphEntryView(null, it.module(), it.entryKey(), it.name(),
                        it.description(), null, it.status(), it.version(), "builtin",
                        null, null, null));
            }
        }
        // 追加用户自建项（非内置 key）
        for (AgentGraphEntry e : users.values()) {
            if (module != null && !module.isBlank() && !module.equals(e.getModule())) {
                continue;
            }
            if (builtinKeys().contains(e.getModule() + ":" + e.getEntryKey())) {
                continue;
            }
            out.add(toView(e, "user"));
        }
        return out;
    }

    /** 新建图谱登记（纯 CRUD，不触发 Agent）。 */
    @Transactional
    public AgentGraphEntryView saveEntry(AgentGraphEntryView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        validateModule(req.module());
        if (req.entryKey() == null || req.entryKey().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "entry_key 不能为空");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称不能为空");
        }
        AgentGraphEntry e = new AgentGraphEntry();
        e.setTenantId(tenantId);
        e.setModule(req.module().trim());
        e.setEntryKey(req.entryKey().trim());
        e.setName(req.name().trim());
        e.setDescription(req.description());
        e.setPayload(jsonOrNull(req.payload()));
        e.setStatus(normalizeStatus(req.status()));
        e.setVersion(req.version());
        e.setCreatedBy(operator);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        graphMapper.insert(e);
        return toView(e, "user");
    }

    /** 编辑图谱登记。 */
    @Transactional
    public AgentGraphEntryView updateEntry(Long id, AgentGraphEntryView.SaveRequest req, String operator) {
        AgentGraphEntry e = requireEntry(id);
        validateModule(req.module());
        if (req.entryKey() == null || req.entryKey().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "entry_key 不能为空");
        }
        e.setModule(req.module().trim());
        e.setEntryKey(req.entryKey().trim());
        if (req.name() != null) {
            e.setName(req.name().trim());
        }
        e.setDescription(req.description());
        if (req.payload() != null) {
            e.setPayload(jsonOrNull(req.payload()));
        }
        if (req.status() != null) {
            e.setStatus(normalizeStatus(req.status()));
        }
        if (req.version() != null) {
            e.setVersion(req.version());
        }
        e.setUpdatedAt(Instant.now());
        graphMapper.updateById(e);
        return toView(e, "user");
    }

    /** 删除图谱登记。 */
    @Transactional
    public void deleteEntry(Long id) {
        AgentGraphEntry e = requireEntry(id);
        graphMapper.deleteById(e.getId());
    }

    /** 状态开关（ENABLED/DISABLED，不触发 Agent）。 */
    @Transactional
    public AgentGraphEntryView setStatus(Long id, String status) {
        AgentGraphEntry e = requireEntry(id);
        e.setStatus(normalizeStatus(status));
        e.setUpdatedAt(Instant.now());
        graphMapper.updateById(e);
        return toView(e, "user");
    }

    // ---------- 洞察 ----------

    /**
     * 洞察：组装监控 digest → AgentPolicy.run(COCKPIT) → audit_log → 300s 缓存。
     * force=true 绕过缓存重新生成；缓存未命中时生成并回填。
     */
    public CockpitInsightView insights(boolean force, String operator) {
        Long tenantId = TenantContext.require();
        String key = INSIGHT_CACHE_PREFIX + tenantId;
        if (!force) {
            RBucket<String> bucket = redisson.getBucket(key);
            String cached = bucket.get();
            if (cached != null) {
                try {
                    return json.readValue(cached, CockpitInsightView.class);
                } catch (Exception ignored) {
                    // 缓存损坏视为未命中，重新生成
                }
            }
        }

        ObjectNode digest = buildDigest(tenantId);
        CockpitInsightModel planner = new CockpitInsightModel();
        AgentOutcome outcome = AgentPolicy.run(cockpitInsightAgent, planner, planner,
                "cockpit_insights", digest, AgentRunConfig.defaults());
        if (outcome.output() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "洞察生成失败（确定性兜底也失效）: " + outcome.reason());
        }
        persistAudit(tenantId, outcome, operator);

        CockpitInsightView view = new CockpitInsightView(
                Instant.parse(outcome.output().path("generated_at").asText(Instant.now().toString())),
                outcome.output().path("overall_health").asInt(100),
                insightsOf(outcome.output().path("insights")));
        try {
            RBucket<String> bucket = redisson.getBucket(key);
            bucket.set(json.writeValueAsString(view));
            bucket.expire(java.time.Duration.ofSeconds(INSIGHT_CACHE_TTL_SECONDS));
        } catch (Exception e) {
            // 缓存失败不影响返回（洞察已生成 + 已审计）
            org.slf4j.LoggerFactory.getLogger(CockpitService.class)
                    .warn("洞察缓存写入失败: {}", e.getMessage());
        }
        return view;
    }

    // ---------- LLM 追踪 ----------

    /** 最近 N 条调用追踪（默认 20，上限 100）；trace 非空时按运行追踪 ID（评测报告联动）过滤。 */
    public List<LlmTraceView> llmTraces(int limit, String trace) {
        Long tenantId = TenantContext.require();
        int n = Math.min(Math.max(limit, 1), 100);
        if (trace != null && !trace.isBlank()) {
            return auditMapper.selectByTrace(tenantId, trace.trim(), n).stream().map(a -> new LlmTraceView(
                    a.getId(), a.getAgentType(), a.getAction(), a.getStatus(), a.getReason(), a.getModel(),
                    a.getTokens(), a.getDurationMs(), a.getCost(), a.getConfidence(), a.getSchemaValid(),
                    a.getOperator(), a.getCreatedAt())).toList();
        }
        return auditMapper.selectRecent(tenantId, n).stream().map(a -> new LlmTraceView(
                a.getId(), a.getAgentType(), a.getAction(), a.getStatus(), a.getReason(), a.getModel(),
                a.getTokens(), a.getDurationMs(), a.getCost(), a.getConfidence(), a.getSchemaValid(),
                a.getOperator(), a.getCreatedAt())).toList();
    }

    // ---------- 内部 ----------

    /** 近 7 天 LLM 聚合（行 + 加权汇总）。 */
    private LlmAgg llmAggregate(Long tenantId) {
        List<Map<String, Object>> rows = auditMapper.selectAgentStats(tenantId);
        List<Map<String, Object>> modelRows = auditMapper.selectModelStats(tenantId);
        List<Map<String, Object>> trendRows = auditMapper.selectDailyTrend(tenantId);
        long totalValid = 0;
        for (Map<String, Object> r : rows) {
            totalValid += lng(r, "schema_valid");
        }
        LlmAgg agg = new LlmAgg(rows, modelRows, trendRows);
        for (Map<String, Object> r : rows) {
            long c = lng(r, "calls");
            agg.calls += c;
            agg.success += lng(r, "success");
            agg.fallback += lng(r, "fallback");
            agg.error += lng(r, "error");
            agg.sumTokens += lng(r, "sum_tokens");
            agg.sumCost = agg.sumCost.add(bd(r, "sum_cost"));
            agg.weightedDuration += c * dbl(r, "avg_duration_ms");
        }
        agg.avgDurationMs = agg.calls > 0 ? agg.weightedDuration / agg.calls : 0;
        agg.schemaValidRate = agg.calls > 0 ? (double) totalValid / agg.calls : 1.0;
        agg.errorRate = agg.calls > 0 ? (double) agg.error / agg.calls : 0;
        agg.fallbackRate = agg.calls > 0 ? (double) agg.fallback / agg.calls : 0;

        // 合并 llm_usage（聊天明细）:chatCalls 供卡面「调用」= audit + 聊天；chatUsageTokens = 聊天专属 token。
        // 速率/时长/成本保持 audit 口径（llm_usage 无状态字段）；rounds 仅聊天计。
        Map<String, Object> usage = llmUsageService.aggregate(tenantId);
        agg.chatCalls = lng(usage, "chat_calls");
        agg.rounds = lng(usage, "rounds");
        agg.sumInputTokens = lng(usage, "input_tokens");
        agg.sumOutputTokens = lng(usage, "output_tokens");
        agg.sumCachedTokens = lng(usage, "cached_tokens");
        agg.usageTokens = agg.sumInputTokens + agg.sumOutputTokens;
        return agg;
    }

    /**
     * 近 7 天最近一次对话 LLM 调用输入构成。
     *
     * <p>主源 = 查询期 AgentState 实时派生：最近聊天会话（llm_usage 台账定位，含提问轮次行，
     * LLM 未启用也记）→ 对应 delegate 的会话转录 → 剔除末尾最终回复（不在任何模型输入内）→
     * 连同该 agent 注册的工具 Schema 过共享估算器。会话/转录缺失时回退 llm_usage.context 快照；
     * 均无 → null（LLM 未启用且无聊天会话）。</p>
     */
    private CockpitOverviewView.LlmContext lastChatContext(Long tenantId) {
        Map<String, Object> session = llmUsageService.lastChatSession(tenantId);
        if (session != null) {
            String agentType = (String) session.get("agent_type");
            String sessionId = (String) session.get("session_id");
            if (agentType != null && sessionId != null) {
                ReActAgent delegate = chatDelegate(agentType);
                if (delegate != null) {
                    try {
                        AgentState state = delegate.getAgentState(String.valueOf(tenantId), sessionId);
                        List<Msg> transcript = state != null ? state.getContext() : null;
                        if (transcript != null && !transcript.isEmpty()) {
                            List<Msg> modelInput = withoutFinalReply(transcript);
                            String derived = LlmContextEstimator.compose(modelInput,
                                    delegate.getToolkit().getToolSchemas());
                            CockpitOverviewView.LlmContext ctx = parseLlmContext(derived);
                            if (ctx != null) {
                                return ctx;
                            }
                        }
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(CockpitService.class)
                                .warn("AgentState 上下文构成派生失败（回退 llm_usage 快照）: {}", e.getMessage());
                    }
                }
            }
        }
        // 兜底：llm_usage.context 快照（会话状态已过期清理时仍可展示）
        return parseLlmContext(llmUsageService.lastChatContext(tenantId));
    }

    /** 聊天 agent 名 → delegate；非 assistant/workflow-dialogue 无对应 bean，返回 null。 */
    private ReActAgent chatDelegate(String agentType) {
        return switch (agentType) {
            case "assistant" -> assistantAgent.getDelegate();
            case "workflow-dialogue" -> workflowDialogueAgent.getDelegate();
            default -> null;
        };
    }

    /**
     * 最近一次模型输入 = 会话转录去掉末尾最终回复：转录末尾 ASSISTANT 消息是本次调用的输出，
     * 不在任何模型输入内（中间 ASSISTANT 工具调用消息属于后续输入，保留）。
     */
    private static List<Msg> withoutFinalReply(List<Msg> transcript) {
        int size = transcript.size();
        if (size > 0 && transcript.get(size - 1).getRole() == MsgRole.ASSISTANT) {
            return transcript.subList(0, size - 1);
        }
        return transcript;
    }

    /** 解析构成 JSON {@code {entries, tokens, categories:[{key,entries,tokens}]}}；空/异常 → null。 */
    private CockpitOverviewView.LlmContext parseLlmContext(String ctxJson) {
        if (ctxJson == null || ctxJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(ctxJson);
            List<CockpitOverviewView.LlmContextCategory> categories = new ArrayList<>();
            for (JsonNode c : root.path("categories")) {
                categories.add(new CockpitOverviewView.LlmContextCategory(
                        c.path("key").asText(), c.path("entries").asInt(), c.path("tokens").asLong()));
            }
            return new CockpitOverviewView.LlmContext(
                    root.path("entries").asInt(), root.path("tokens").asLong(), categories);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(CockpitService.class)
                    .warn("llm_usage 上下文构成解析失败（跳过构成展示）: {}", e.getMessage());
            return null;
        }
    }

    /** 洞察 digest（CockpitInsightModel 入参结构，见其类注释）。 */
    private ObjectNode buildDigest(Long tenantId) {
        LlmAgg agg = llmAggregate(tenantId);
        ObjectNode llmNode = json.createObjectNode();
        llmNode.put("total_calls", agg.calls);
        llmNode.put("success", agg.success);
        llmNode.put("fallback", agg.fallback);
        llmNode.put("error", agg.error);
        llmNode.put("avg_duration_ms", round4(agg.avgDurationMs));
        llmNode.put("total_tokens", agg.sumTokens);
        llmNode.put("total_cost", round4(agg.sumCost.doubleValue()));
        llmNode.put("schema_valid_rate", round4(agg.schemaValidRate));
        llmNode.put("error_rate", round4(agg.errorRate));
        llmNode.put("fallback_rate", round4(agg.fallbackRate));
        ArrayNode trend = llmNode.putArray("trend");
        for (Map<String, Object> r : agg.trendRows) {
            ObjectNode d = trend.addObject();
            d.put("day", str(r, "day"));
            d.put("calls", lng(r, "calls"));
            d.put("tokens", lng(r, "sum_tokens"));
            d.put("cost", round4(bd(r, "sum_cost").doubleValue()));
        }

        // 图谱 total/enabled（口径与 overview 一致）
        CockpitOverviewView.GraphOverview g = overviewGraphCounts(tenantId);

        long docs = kbDocumentMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getTenantId, tenantId));
        long chunks = kbChunkMapper.selectCount(new LambdaQueryWrapper<KbDocumentChunk>()
                .eq(KbDocumentChunk::getTenantId, tenantId));
        long datasets = datasetMapper.selectCount(new LambdaQueryWrapper<EvaluationDataset>()
                .eq(EvaluationDataset::getTenantId, tenantId));
        long reports = reportMapper.selectCount(new LambdaQueryWrapper<EvaluationReport>()
                .eq(EvaluationReport::getTenantId, tenantId));

        ObjectNode graph = json.createObjectNode();
        graph.put("total", g.total());
        graph.put("enabled", g.enabled());
        ObjectNode knowledge = json.createObjectNode();
        knowledge.put("docs", docs);
        knowledge.put("chunks", chunks);
        ObjectNode evaluation = json.createObjectNode();
        evaluation.put("datasets", datasets);
        evaluation.put("reports", reports);

        ObjectNode digest = json.createObjectNode();
        digest.put("llm_enabled", llm.isEnabled());
        digest.put("model_id", llm.getModelId());
        digest.set("llm", llmNode);
        digest.set("graph", graph);
        digest.set("knowledge", knowledge);
        digest.put("memory_keys", memoryKeys());
        digest.set("evaluation", evaluation);
        return digest;
    }

    private CockpitOverviewView.GraphOverview overviewGraphCounts(Long tenantId) {
        Map<String, long[]> perModule = new LinkedHashMap<>();
        for (String m : AgentCatalogRegistry.MODULES) {
            perModule.put(m, new long[]{0, 0});
        }
        for (AgentCatalogRegistry.Item it : AgentCatalogRegistry.all()) {
            long[] c = perModule.get(it.module());
            c[0]++;
            if ("ENABLED".equals(it.status())) {
                c[1]++;
            }
        }
        Map<String, AgentGraphEntry> userRows = userEntries(tenantId);
        for (AgentGraphEntry e : userRows.values()) {
            long[] c = perModule.get(e.getModule());
            if (c == null) {
                continue;
            }
            boolean wasBuiltin = builtinKeys().contains(e.getModule() + ":" + e.getEntryKey());
            if (wasBuiltin) {
                c[0]--;
                if ("ENABLED".equals(e.getStatus())) {
                    c[1]--;
                }
            }
            c[0]++;
            if ("ENABLED".equals(e.getStatus())) {
                c[1]++;
            }
        }
        long total = 0;
        long enabled = 0;
        for (String m : AgentCatalogRegistry.MODULES) {
            total += perModule.get(m)[0];
            enabled += perModule.get(m)[1];
        }
        return new CockpitOverviewView.GraphOverview(total, enabled, List.of());
    }

    /** 当前租户用户登记行（module:entry_key → 行）。 */
    private Map<String, AgentGraphEntry> userEntries(Long tenantId) {
        return graphMapper.selectList(new LambdaQueryWrapper<AgentGraphEntry>()
                        .eq(AgentGraphEntry::getTenantId, tenantId)
                        .orderByAsc(AgentGraphEntry::getCreatedAt))
                .stream().collect(Collectors.toMap(
                        e -> e.getModule() + ":" + e.getEntryKey(), e -> e, (a, b) -> b));
    }

    private java.util.Set<String> builtinKeys() {
        return AgentCatalogRegistry.all().stream()
                .map(it -> it.module() + ":" + it.entryKey())
                .collect(Collectors.toSet());
    }

    /** Redis agentscope 键计数（会话状态 + 工作区数据）。 */
    private long memoryKeys() {
        long keys = 0;
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> r = jedis.scan(cursor,
                    new ScanParams().match("easysys:agentscope:*").count(1000));
            keys += r.getResult().size();
            cursor = r.getCursor();
        } while (!"0".equals(cursor));
        return keys;
    }

    /** audit_log 持久化（与 StrategyService.persistAudit 同实现，agentType 取 outcome）。 */
    public void persistAudit(Long tenantId, AgentOutcome outcome, String operator) {
        AgentAudit a = new AgentAudit();
        a.setTenantId(tenantId);
        a.setAgentType(outcome.audit().agentType().name());
        a.setAction(outcome.audit().action());
        a.setStatus(outcome.status());
        a.setReason(outcome.reason());
        a.setInputSummary(writeOrNull(outcome.audit().inputSummary()));
        a.setOutput(writeOrNull(outcome.audit().output()));
        a.setSchemaValid(!"ERROR".equals(outcome.status())
                && (outcome.reason() == null || !outcome.reason().contains("invalid")));
        a.setStrategyVersion(outcome.audit().strategyVersion());
        a.setConfidence(outcome.audit().confidence() == null
                ? null : BigDecimal.valueOf(outcome.audit().confidence()));
        a.setModel(outcome.audit().model());
        a.setTokens(outcome.audit().tokens());
        a.setDurationMs(outcome.audit().durationMs());
        a.setOperator(operator);
        a.setCreatedAt(Instant.now());
        auditMapper.insert(a);
    }

    private AgentGraphEntry requireEntry(Long id) {
        Long tenantId = TenantContext.require();
        AgentGraphEntry e = graphMapper.selectById(id);
        if (e == null || !tenantId.equals(e.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "图谱登记不存在: " + id);
        }
        return e;
    }

    private void validateModule(String module) {
        if (module == null || !AgentCatalogRegistry.MODULES.contains(module.trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "非法 module（应为 " + AgentCatalogRegistry.MODULES + "）: " + module);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ENABLED";
        }
        String v = status.trim().toUpperCase();
        if (!"ENABLED".equals(v) && !"DISABLED".equals(v)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法状态（ENABLED/DISABLED）: " + status);
        }
        return v;
    }

    private List<CockpitInsightView.Insight> insightsOf(JsonNode insights) {
        List<CockpitInsightView.Insight> out = new ArrayList<>();
        if (insights != null && insights.isArray()) {
            for (JsonNode n : insights) {
                out.add(new CockpitInsightView.Insight(
                        n.path("level").asText("info"),
                        n.path("dimension").asText(""),
                        n.path("detail").asText(""),
                        n.path("suggestion").isNull() ? null : n.path("suggestion").asText()));
            }
        }
        return out;
    }

    private AgentGraphEntryView toView(AgentGraphEntry e, String source) {
        return new AgentGraphEntryView(e.getId(), e.getModule(), e.getEntryKey(), e.getName(),
                e.getDescription(), parse(e.getPayload()), e.getStatus(), e.getVersion(), source,
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String jsonOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(n);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "JSON 序列化失败: " + e.getMessage());
        }
    }

    private String writeOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(n);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static final java.util.Set<String> BUILTIN_KEYS = AgentCatalogRegistry.all().stream()
            .map(it -> it.module() + ":" + it.entryKey())
            .collect(java.util.stream.Collectors.toSet());
    private static final Map<String, String> AGENT_LABELS = Map.of(
            "LAYER", "分层策略制定",
            "ROUTER", "触达路由决策",
            "CHURN", "流失风险评测",
            "WORKFLOW", "工作流 DAG 生成",
            "COCKPIT", "驾驶舱洞察",
            "EVALUATION", "评测执行");

    private String agentLabel(AgentType t) {
        return AGENT_LABELS.getOrDefault(t.name(), t.name());
    }

    private static String str(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double dbl(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal bd(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v == null) {
            return ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return ZERO;
        }
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** LLM 聚合中间态。 */
    private static final class LlmAgg {
        final List<Map<String, Object>> rows;
        final List<Map<String, Object>> modelRows;
        final List<Map<String, Object>> trendRows;
        long calls;
        long success;
        long fallback;
        long error;
        long sumTokens;
        BigDecimal sumCost = ZERO;
        double weightedDuration;
        double avgDurationMs;
        double schemaValidRate;
        double errorRate;
        double fallbackRate;
        long chatCalls;
        long rounds;
        long sumInputTokens;
        long sumOutputTokens;
        long sumCachedTokens;
        long usageTokens;

        LlmAgg(List<Map<String, Object>> rows, List<Map<String, Object>> modelRows,
               List<Map<String, Object>> trendRows) {
            this.rows = rows;
            this.modelRows = modelRows;
            this.trendRows = trendRows;
        }
    }
}