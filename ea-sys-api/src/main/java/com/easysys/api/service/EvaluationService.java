package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.agent.AgentOutcome;
import com.easysys.agent.AgentPolicy;
import com.easysys.agent.AgentRunConfig;
import com.easysys.agent.EvaluationModel;
import com.easysys.api.config.AgentLlmProperties;
import com.easysys.api.dto.evaluation.CaseView;
import com.easysys.api.dto.evaluation.CustomEvaluatorView;
import com.easysys.api.dto.evaluation.DatasetView;
import com.easysys.api.dto.evaluation.EvaluationRunRequest;
import com.easysys.api.dto.evaluation.ImportResultView;
import com.easysys.api.dto.evaluation.ReportView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.EvaluationCase;
import com.easysys.api.entity.EvaluationCustomEvaluator;
import com.easysys.api.entity.EvaluationDataset;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.EvaluationCaseMapper;
import com.easysys.api.mapper.EvaluationCustomEvaluatorMapper;
import com.easysys.api.mapper.EvaluationDatasetMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测中心：数据集 + 用例 + 批量运行（AgentPolicy 确定性评测模型 + 审计）+ 报告回看。
 *
 * <p>运行模式：openjudge 用用例预置响应（provided_response）直接判分，跳过被测智能体执行；
 * execute 模式需要被测智能体链路接入（当前阶段未接入，运行时报错提示，见 {@link #run}）。
 * 评测器为代码内置常量目录（11 个），数据集模式/范围决定判分输入。</p>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** execute 模式单用例执行超时（被测智能体确定性执行，超时用例视为不适用跳过判分）。 */
    private static final Duration EXECUTE_TIMEOUT = Duration.ofSeconds(30);

    private final EvaluationDatasetMapper datasetMapper;
    private final EvaluationCaseMapper caseMapper;
    private final EvaluationReportMapper reportMapper;
    private final AgentAuditMapper auditMapper;
    private final EvaluationCustomEvaluatorMapper customEvaluatorMapper;
    private final LlmJudgeScorer judgeScorer;
    private final HarnessAgent evaluationAgent;
    private final HarnessAgent assistantAgent;
    private final HarnessAgent workflowDialogueAgent;
    private final AgentLlmProperties llm;
    private final ObjectMapper json;

    /** LLM-Judge 判分并行池：8 并发足够（判分互相独立，串行 36 次判分曾拖慢全量 run 至 2 分钟）。 */
    private static final ExecutorService JUDGE_POOL = Executors.newFixedThreadPool(8);

    public EvaluationService(EvaluationDatasetMapper datasetMapper, EvaluationCaseMapper caseMapper,
                             EvaluationReportMapper reportMapper, AgentAuditMapper auditMapper,
                             EvaluationCustomEvaluatorMapper customEvaluatorMapper,
                             LlmJudgeScorer judgeScorer,
                             HarnessAgent evaluationAgent,
                             @Qualifier("assistantAgent") HarnessAgent assistantAgent,
                             @Qualifier("workflowDialogueAgent") HarnessAgent workflowDialogueAgent,
                             AgentLlmProperties llm, ObjectMapper json) {
        this.datasetMapper = datasetMapper;
        this.caseMapper = caseMapper;
        this.reportMapper = reportMapper;
        this.auditMapper = auditMapper;
        this.customEvaluatorMapper = customEvaluatorMapper;
        this.judgeScorer = judgeScorer;
        this.evaluationAgent = evaluationAgent;
        this.assistantAgent = assistantAgent;
        this.workflowDialogueAgent = workflowDialogueAgent;
        this.llm = llm;
        this.json = json;
    }

    // ---------- 数据集 ----------

    public List<DatasetView> listDatasets() {
        Long tenantId = TenantContext.require();
        return datasetMapper.selectList(new LambdaQueryWrapper<EvaluationDataset>()
                        .eq(EvaluationDataset::getTenantId, tenantId)
                        .orderByDesc(EvaluationDataset::getCreatedAt))
                .stream().map(this::toDatasetView).toList();
    }

    @Transactional
    public DatasetView createDataset(DatasetView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        validateDataset(req);
        EvaluationDataset d = new EvaluationDataset();
        d.setTenantId(tenantId);
        d.setName(req.name().trim());
        d.setDescription(req.description());
        d.setScope(req.scope() == null || req.scope().isBlank() ? "llm_call" : req.scope().trim());
        d.setMode(req.mode() == null || req.mode().isBlank() ? "openjudge" : req.mode().trim());
        d.setAgentType(req.agentType() == null || req.agentType().isBlank() ? "assistant" : req.agentType().trim());
        d.setStatus("ENABLED");
        d.setCreatedBy(operator);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        datasetMapper.insert(d);
        return toDatasetView(d);
    }

    @Transactional
    public DatasetView updateDataset(Long id, DatasetView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationDataset d = requireDataset(id, tenantId);
        if (req.name() != null && !req.name().isBlank()) {
            d.setName(req.name().trim());
        }
        d.setDescription(req.description());
        if (req.mode() != null && !req.mode().isBlank()) {
            d.setMode(req.mode().trim());
        }
        if (req.agentType() != null && !req.agentType().isBlank()) {
            d.setAgentType(req.agentType().trim());
        }
        if (req.status() != null && !req.status().isBlank()) {
            String s = req.status().trim().toUpperCase();
            if (!"ENABLED".equals(s) && !"DISABLED".equals(s)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "非法状态（ENABLED/DISABLED）: " + req.status());
            }
            d.setStatus(s);
        }
        d.setUpdatedAt(Instant.now());
        datasetMapper.updateById(d);
        return toDatasetView(d);
    }

    /** 删除数据集：级联软删用例与历史报告（均含 deleted 列）。 */
    @Transactional
    public void deleteDataset(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationDataset d = requireDataset(id, tenantId);
        datasetMapper.deleteById(d.getId());
        caseMapper.delete(new LambdaQueryWrapper<EvaluationCase>()
                .eq(EvaluationCase::getDatasetId, d.getId()));
        reportMapper.delete(new LambdaQueryWrapper<EvaluationReport>()
                .eq(EvaluationReport::getDatasetId, d.getId()));
    }

    // ---------- 用例 ----------

    public List<CaseView> listCases(Long datasetId) {
        Long tenantId = TenantContext.require();
        requireDataset(datasetId, tenantId);
        return caseMapper.selectList(new LambdaQueryWrapper<EvaluationCase>()
                        .eq(EvaluationCase::getDatasetId, datasetId)
                        .orderByAsc(EvaluationCase::getSeq)
                        .orderByAsc(EvaluationCase::getId))
                .stream().map(this::toCaseView).toList();
    }

    @Transactional
    public CaseView addCase(Long datasetId, CaseView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        requireDataset(datasetId, tenantId);
        if (req.question() == null || req.question().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用例 question 不能为空");
        }
        EvaluationCase c = new EvaluationCase();
        c.setTenantId(tenantId);
        c.setDatasetId(datasetId);
        c.setSeq(req.seq() == null ? nextSeq(datasetId) : req.seq());
        c.setQuestion(req.question());
        c.setSystemPrompt(req.systemPrompt());
        c.setExpectedOutput(jsonOrNull(req.expectedOutput()));
        c.setToolSchema(jsonOrNull(req.toolSchema()));
        c.setExpectedTool(jsonOrNull(req.expectedTool()));
        c.setExpectedSteps(req.expectedSteps() == null ? 1 : req.expectedSteps());
        c.setExpectedPolicy(jsonOrNull(req.expectedPolicy()));
        c.setProvidedResponse(req.providedResponse());
        c.setCreatedAt(Instant.now());
        caseMapper.insert(c);
        return toCaseView(c);
    }

    @Transactional
    public CaseView updateCase(Long id, CaseView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationCase c = requireCase(id, tenantId);
        if (req.seq() != null) {
            c.setSeq(req.seq());
        }
        if (req.question() != null && !req.question().isBlank()) {
            c.setQuestion(req.question());
        }
        c.setSystemPrompt(req.systemPrompt() == null ? c.getSystemPrompt() : req.systemPrompt());
        if (req.expectedOutput() != null) {
            c.setExpectedOutput(jsonOrNull(req.expectedOutput()));
        }
        if (req.toolSchema() != null) {
            c.setToolSchema(jsonOrNull(req.toolSchema()));
        }
        if (req.expectedTool() != null) {
            c.setExpectedTool(jsonOrNull(req.expectedTool()));
        }
        if (req.expectedSteps() != null) {
            c.setExpectedSteps(req.expectedSteps());
        }
        if (req.expectedPolicy() != null) {
            c.setExpectedPolicy(jsonOrNull(req.expectedPolicy()));
        }
        if (req.providedResponse() != null) {
            c.setProvidedResponse(req.providedResponse());
        }
        caseMapper.updateById(c);
        return toCaseView(c);
    }

    @Transactional
    public void deleteCase(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationCase c = requireCase(id, tenantId);
        caseMapper.deleteById(c.getId());
    }

    /**
     * jsonl 数据集导入：body 为逐行 JSON 对象（jsonl）或整体 JSON 数组。
     * 字段映射：question（必填）→ 用例提问；reference → 期望输出（expected_output）；
     * response → 预置响应（provided_response）；system_prompt → 系统提示词；meta 忽略。
     * 坏行/缺 question 行记入 errors（行号从 1 计）并跳过，不整批失败。
     */
    @Transactional
    public ImportResultView importCases(Long datasetId, String content, String operator) {
        Long tenantId = TenantContext.require();
        requireDataset(datasetId, tenantId);
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导入内容为空");
        }
        List<ImportResultView.LineError> errors = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int maxSeq = nextSeq(datasetId) - 1;

        // 解析：JSON 数组整体 / jsonl 逐行（物理行号记入错误）
        List<Object[]> rows = new ArrayList<>(); // [行号, JsonNode]
        String trimmed = content.trim();
        try {
            if (trimmed.startsWith("[")) {
                JsonNode arr = json.readTree(trimmed);
                if (!arr.isArray()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "导入内容 JSON 数组非法");
                }
                int line = 1;
                for (JsonNode row : arr) {
                    rows.add(new Object[]{line++, row});
                }
            } else {
                String[] lines = trimmed.split("\\R");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        rows.add(new Object[]{i + 1, json.readTree(line)});
                    } catch (Exception e) {
                        errors.add(new ImportResultView.LineError(i + 1, "JSON 解析失败: " + e.getMessage()));
                        skipped++;
                    }
                }
            }
        } catch (Exception e) {
            errors.add(new ImportResultView.LineError(1, "整体解析失败（jsonl 逐行或 JSON 数组）: " + e.getMessage()));
            skipped++;
        }

        for (Object[] row : rows) {
            int lineNo = (Integer) row[0];
            JsonNode node = (JsonNode) row[1];
            if (node == null || !node.isObject()) {
                errors.add(new ImportResultView.LineError(lineNo, "该行不是 JSON 对象"));
                skipped++;
                continue;
            }
            String question = node.path("question").isValueNode() ? node.path("question").asText("") : "";
            if (question.isBlank()) {
                errors.add(new ImportResultView.LineError(lineNo, "缺少必填字段 question"));
                skipped++;
                continue;
            }
            try {
                EvaluationCase c = new EvaluationCase();
                c.setTenantId(tenantId);
                c.setDatasetId(datasetId);
                c.setSeq(maxSeq + 1);
                maxSeq = c.getSeq();
                c.setQuestion(question);
                c.setSystemPrompt(textOrNull(node.path("system_prompt")));
                c.setExpectedOutput(jsonOrNull(node.get("reference")));
                c.setExpectedSteps(1);
                c.setProvidedResponse(textOrNull(node.path("response")));
                c.setCreatedAt(Instant.now());
                caseMapper.insert(c);
                imported++;
            } catch (Exception e) {
                errors.add(new ImportResultView.LineError(lineNo, "写入失败: " + e.getMessage()));
                skipped++;
            }
        }
        log.info("评测数据集 {} 导入完成：成功 {} 行，跳过 {} 行（含 {} 条错误明细）",
                datasetId, imported, skipped, errors.size());
        return new ImportResultView(imported, skipped, errors);
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    // ---------- 批量运行 ----------

    /**
     * 批量运行评测：读数据集+用例 → 组装判分输入 → AgentPolicy.run(EVALUATION) → audit_log →
     * evaluation_report 落库。
     *
     * <p>openjudge：actual_response = 用例预置响应（跳过被测智能体执行）；
     * execute：逐用例真实运行被测智能体（agent_type 决定 assistant / workflow-dialogue），
     * 收集实际响应文本 + 工具调用轨迹（实际步数与期望步数对比），单用例失败/超时视为不适用跳过判分。</p>
     */
    @Transactional
    public ReportView run(EvaluationRunRequest req, String operator) {
        Long tenantId = TenantContext.require();
        if (req.datasetId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "datasetId 不能为空");
        }
        EvaluationDataset d = requireDataset(req.datasetId(), tenantId);
        if ("DISABLED".equals(d.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集已停用，禁止运行评测");
        }
        List<EvaluationCase> cases = caseMapper.selectList(new LambdaQueryWrapper<EvaluationCase>()
                .eq(EvaluationCase::getDatasetId, d.getId())
                .orderByAsc(EvaluationCase::getSeq)
                .orderByAsc(EvaluationCase::getId));
        if (cases.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集无可评测用例，请先添加用例");
        }

        ReActAgent subject = "execute".equals(d.getMode())
                ? subjectDelegate(d.getAgentType()) : null;

        // 判分轮次（1-5 多次取均值）+ 运行追踪 ID（驾驶舱 LLM 调用联动）
        int judgeRounds = req.judgeRounds() == null ? 1 : Math.max(1, Math.min(req.judgeRounds(), 5));
        String traceId = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 租户自定义评测器（逻辑删除由 MyBatis-Plus 自动过滤）
        Map<Long, EvaluationCustomEvaluator> customsById = new HashMap<>();
        for (EvaluationCustomEvaluator ce : customEvaluatorMapper.selectList(
                new LambdaQueryWrapper<EvaluationCustomEvaluator>()
                        .eq(EvaluationCustomEvaluator::getTenantId, tenantId))) {
            customsById.put(ce.getId(), ce);
        }

        ObjectNode input = json.createObjectNode();
        input.put("scope", d.getScope());
        input.put("mode", d.getMode());
        input.put("llm_enabled", llm.isEnabled());
        input.put("trace_id", traceId);
        input.put("judge_rounds", judgeRounds);
        ArrayNode customArr = input.putArray("custom_evaluators");
        for (EvaluationCustomEvaluator ce : customsById.values()) {
            ObjectNode e = customArr.addObject();
            e.put("metric", ce.metric());
            e.put("category", ce.getCategory());
            if ("rule".equals(ce.getCategory())) {
                e.put("rule_type", ce.getRuleType());
                JsonNode params = parse(ce.getParams());
                if (params != null) {
                    e.set("params", params);
                }
            }
        }
        ArrayNode caseArr = input.putArray("cases");
        for (EvaluationCase c : cases) {
            ObjectNode n = caseArr.addObject();
            n.put("seq", c.getSeq());
            n.put("question", c.getQuestion());
            if (c.getSystemPrompt() != null) {
                n.put("system_prompt", c.getSystemPrompt());
            }
            setOrNull(n, "expected_output", c.getExpectedOutput());
            setOrNull(n, "expected_tool", c.getExpectedTool());
            setOrNull(n, "tool_schema", c.getToolSchema());
            n.put("expected_steps", c.getExpectedSteps() == null ? 1 : c.getExpectedSteps());
            setOrNull(n, "expected_policy", c.getExpectedPolicy());
            if (c.getProvidedResponse() != null) {
                n.put("provided_response", c.getProvidedResponse());
                n.put("actual_response", c.getProvidedResponse()); // openjudge：预置响应直接判分
            } else if ("execute".equals(d.getMode())) {
                executeSubject(n, subject, tenantId, operator, c, traceId); // execute：真实运行被测智能体
            }
        }
        ArrayNode evals = input.putArray("evaluators");
        if (req.evaluators() != null && !req.evaluators().isEmpty()) {
            for (String metric : req.evaluators()) {
                EvaluationCustomEvaluator custom = customsById.get(customIdOf(metric));
                if (EvaluationModel.ALL_METRICS.contains(metric) || custom != null) {
                    ObjectNode e = evals.addObject();
                    e.put("metric", metric);
                    e.put("category", custom != null ? custom.getCategory()
                            : (EvaluationModel.RULE_METRICS.contains(metric) ? "rule" : "llm_judge"));
                }
            }
        }

        // LLM 启用时对选中 LLM-Judge 评测器逐 case 真实打分（0-100）注入 judge_scores；
        // 停用/失败/无 apiKey → 不注入，EvaluationModel 走确定性近似降级（不整轮失败）
        injectJudgeScores(input, req.evaluators(), judgeRounds, tenantId, traceId, customsById);

        EvaluationModel planner = new EvaluationModel();
        // 评测判分走 LLM 主位迄今 0 成功（大 JSON schema 输出常超 60s 或不合规），给 15s 尝试额度后即降级确定性，
        // 避免每次 run 空等 60s；其余批处理（洞察/工作流等）保留 defaults() 的长推理额度。
        AgentOutcome outcome = AgentPolicy.run(evaluationAgent, planner, planner,
                "evaluation_run", input, new AgentRunConfig(0.7, 2, 15_000));
        if (outcome.output() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评测运行失败（确定性兜底也失效）: " + outcome.reason());
        }
        persistAudit(tenantId, outcome, operator);

        EvaluationReport r = new EvaluationReport();
        r.setTenantId(tenantId);
        r.setDatasetId(d.getId());
        r.setName(d.getName());
        r.setTotalCases(cases.size());
        r.setTestedCases(outcome.output().path("tested_cases").asInt(cases.size()));
        r.setMetrics(writeOrNull(outcome.output().path("metrics")));
        r.setFindings(writeOrNull(outcome.output().path("findings")));
        r.setSummary(writeOrNull(outcome.output().path("summary")));
        r.setConfidence(BigDecimal.valueOf(outcome.audit().confidence() == null
                ? 1.0 : outcome.audit().confidence()));
        r.setModel(outcome.audit().model() == null ? "deterministic" : outcome.audit().model());
        r.setMode(d.getMode());
        r.setJudgeRounds(judgeRounds);
        r.setTraceId(traceId);
        r.setCreatedBy(operator);
        r.setCreatedAt(Instant.now());
        reportMapper.insert(r);
        return toReportView(r);
    }

    // ---------- 报告 ----------

    public List<ReportView> listReports() {
        Long tenantId = TenantContext.require();
        return reportMapper.selectList(new LambdaQueryWrapper<EvaluationReport>()
                        .eq(EvaluationReport::getTenantId, tenantId)
                        .orderByDesc(EvaluationReport::getCreatedAt))
                .stream().map(this::toReportView).toList();
    }

    public ReportView getReport(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationReport r = reportMapper.selectById(id);
        if (r == null || !tenantId.equals(r.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测报告不存在: " + id);
        }
        return toReportView(r);
    }

    @Transactional
    public void deleteReport(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationReport r = reportMapper.selectById(id);
        if (r == null || !tenantId.equals(r.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测报告不存在: " + id);
        }
        reportMapper.deleteById(r.getId());
    }

    // ---------- 内部 ----------

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

    /** execute 被测智能体 delegate：agent_type → 对应 HarnessAgent 的 ReActAgent，未知返回 null。 */
    private ReActAgent subjectDelegate(String agentType) {
        return switch (agentType) {
            case "assistant" -> assistantAgent.getDelegate();
            case "workflow-dialogue" -> workflowDialogueAgent.getDelegate();
            default -> null;
        };
    }

    /**
     * execute 单用例执行：以唯一 sessionId 运行被测智能体（防会话状态串扰），
     * 成功收集实际响应文本 + 工具调用轨迹；失败/超时/空回复该用例不注入 actual_response
     * （判分器视为不适用，产出 INFO 发现，不中断整轮）。
     */
    private void executeSubject(ObjectNode n, ReActAgent subject, Long tenantId, String operator,
                                EvaluationCase c, String traceId) {
        if (subject == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知被测智能体 execute 目标");
        }
        String userId = String.valueOf(tenantId);
        String sessionId = traceId + "-" + c.getSeq(); // 会话 ID = 运行追踪 ID + 用例序号（驾驶舱按 trace 联动）
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put("tenantId", Long.class, tenantId)
                .put("operator", String.class, operator)
                .build();
        try {
            Msg result = subject.call(List.of(new UserMessage(c.getQuestion())), ctx)
                    .block(EXECUTE_TIMEOUT);
            String text = result == null ? null : result.getTextContent();
            if (text == null || text.isBlank()) {
                log.warn("评测 execute 用例 seq={} 返回空回复，判分跳过（不适用）", c.getSeq());
                return;
            }
            n.put("actual_response", text);

            // 轨迹：从会话状态上下文收集已执行工具调用（ASKING/PENDING 未执行不计入）
            List<ToolUseBlock> executed = new ArrayList<>();
            AgentState state = subject.getAgentState(userId, sessionId);
            if (state != null && state.getContext() != null) {
                for (Msg m : state.getContext()) {
                    for (ToolUseBlock tub : m.getContentBlocks(ToolUseBlock.class)) {
                        if (tub.getState() == ToolCallState.ASKING
                                || tub.getState() == ToolCallState.PENDING) {
                            continue;
                        }
                        executed.add(tub);
                    }
                }
            }
            ArrayNode calls = json.createArrayNode();
            for (ToolUseBlock tub : executed) {
                ObjectNode call = calls.addObject();
                call.put("name", tub.getName());
                call.set("args", json.valueToTree(tub.getInput()));
            }
            n.set("actual_tool_calls", calls);
            n.put("actual_steps", executed.size() + 1); // 工具调用步 + 最终回复步
            log.info("评测 execute seq={} 回复=[{}] calls={}", c.getSeq(), text, calls);
        } catch (Exception e) {
            log.warn("评测 execute 用例 seq={} 执行失败（{}），判分跳过（不适用）", c.getSeq(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * LLM 真实判分注入：对选中（缺省 = 全量内置）LLM-Judge 评测器逐 case 调 Judge 模型打分
     * （0-100，judgeRounds 次取均值）写入 cases[].judge_scores.{metric}。判分失败/停用不注入，
     * EvaluationModel 走确定性近似降级；rule 类（内置 + 自定义）不参与。
     */
    private void injectJudgeScores(ObjectNode input, List<String> metrics, int rounds, Long tenantId,
                                   String traceId, Map<Long, EvaluationCustomEvaluator> customsById) {
        if (rounds <= 0) {
            return;
        }
        if (metrics == null || metrics.isEmpty()) {
            metrics = EvaluationModel.ALL_METRICS;
        }
        ArrayNode caseArr = (ArrayNode) input.path("cases");
        // 判分调用互相独立（LLM 生成慢、逐个串行会拖全量 run 数分钟），并行提交后按原顺序写回；
        // score 内部已捕获全部异常返回 null，join 不会抛；upsertCall 为单语句原子更新，并发记账安全。
        List<JudgeTask> tasks = new ArrayList<>();
        for (JsonNode cn : caseArr) {
            if (text(cn.path("actual_response")).isEmpty()) {
                continue; // 与 EvaluationModel 判空口径一致：无实际响应不判分
            }
            for (String metric : metrics) {
                if (EvaluationModel.RULE_METRICS.contains(metric)) {
                    continue; // 规则类评测器确定性判分，不走 LLM
                }
                EvaluationCustomEvaluator custom = customsById.get(customIdOf(metric));
                if (custom != null && !"llm_judge".equals(custom.getCategory())) {
                    continue; // rule 类自定义评测器同规则判分
                }
                tasks.add(new JudgeTask(cn, metric, custom != null ? custom.getJudgePrompt()
                        : LlmJudgeScorer.defaultPrompt(metric),
                        text(cn.path("question")), text(cn.path("actual_response")),
                        text(cn.path("expected_output"))));
            }
        }
        if (tasks.isEmpty()) {
            return;
        }
        List<CompletableFuture<Double>> futures = new ArrayList<>(tasks.size());
        for (JudgeTask t : tasks) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    judgeScorer.score(t.metric(), t.prompt(), t.question(), t.response(),
                            t.expected(), rounds, tenantId, traceId), JUDGE_POOL));
        }
        for (int i = 0; i < tasks.size(); i++) {
            Double s = futures.get(i).join();
            if (s != null) {
                JudgeTask t = tasks.get(i);
                ObjectNode judgeScores = t.caseNode().path("judge_scores").isObject()
                        ? (ObjectNode) t.caseNode().path("judge_scores") : null;
                if (judgeScores == null) {
                    judgeScores = ((ObjectNode) t.caseNode()).putObject("judge_scores");
                }
                judgeScores.put(t.metric(), Math.round(s));
            }
        }
    }

    /** 一条 LLM-Judge 判分任务（并行提交，写回按原顺序）。 */
    private record JudgeTask(JsonNode caseNode, String metric, String prompt,
                             String question, String response, String expected) {
    }

    /** 指标名 custom_{id} → 自定义评测器 id；非 custom / 非法返回 null。 */
    private static Long customIdOf(String metric) {
        if (metric == null || !metric.startsWith("custom_")) {
            return null;
        }
        try {
            return Long.parseLong(metric.substring("custom_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JSON 节点文本（对象/数组取序列化文本；仅判空给注入循环用）。 */
    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    private void validateDataset(DatasetView.SaveRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集名称不能为空");
        }
        String scope = req.scope() == null ? "llm_call" : req.scope();
        String mode = req.mode() == null ? "openjudge" : req.mode();
        String agentType = req.agentType() == null ? "assistant" : req.agentType();
        if (!"llm_call".equals(scope)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 scope（仅 llm_call）: " + scope);
        }
        if (!"openjudge".equals(mode) && !"execute".equals(mode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 mode（openjudge/execute）: " + mode);
        }
        if (!"assistant".equals(agentType) && !"workflow-dialogue".equals(agentType)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "非法被测智能体 agent_type（assistant/workflow-dialogue）: " + agentType);
        }
    }

    private EvaluationDataset requireDataset(Long id, Long tenantId) {
        EvaluationDataset d = datasetMapper.selectById(id);
        if (d == null || !tenantId.equals(d.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测数据集不存在: " + id);
        }
        return d;
    }

    private EvaluationCase requireCase(Long id, Long tenantId) {
        EvaluationCase c = caseMapper.selectById(id);
        if (c == null || !tenantId.equals(c.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测用例不存在: " + id);
        }
        return c;
    }

    private Integer nextSeq(Long datasetId) {
        return caseMapper.selectList(new LambdaQueryWrapper<EvaluationCase>()
                        .eq(EvaluationCase::getDatasetId, datasetId)
                        .orderByDesc(EvaluationCase::getSeq)
                        .last("LIMIT 1"))
                .stream().findFirst().map(c -> c.getSeq() + 1).orElse(1);
    }

    private void setOrNull(ObjectNode n, String field, String raw) {
        JsonNode node = parse(raw);
        if (node != null && !node.isNull()) {
            n.set(field, node);
        }
    }

    private String jsonOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(node);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "JSON 序列化失败: " + e.getMessage());
        }
    }

    private String writeOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(node);
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

    private DatasetView toDatasetView(EvaluationDataset d) {
        Long count = caseMapper.selectCount(new LambdaQueryWrapper<EvaluationCase>()
                .eq(EvaluationCase::getDatasetId, d.getId()));
        return new DatasetView(d.getId(), d.getName(), d.getDescription(), d.getScope(), d.getMode(),
                d.getAgentType(), d.getStatus(), count == null ? 0 : count.intValue(), d.getCreatedBy(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private CaseView toCaseView(EvaluationCase c) {
        return new CaseView(c.getId(), c.getDatasetId(), c.getSeq(), c.getQuestion(),
                c.getSystemPrompt(), parse(c.getExpectedOutput()), parse(c.getToolSchema()),
                parse(c.getExpectedTool()), c.getExpectedSteps(), parse(c.getExpectedPolicy()),
                c.getProvidedResponse(), c.getCreatedAt());
    }

    // ---------- 自定义评测器 ----------

    public List<CustomEvaluatorView> listCustomEvaluators() {
        Long tenantId = TenantContext.require();
        return customEvaluatorMapper.selectList(new LambdaQueryWrapper<EvaluationCustomEvaluator>()
                        .eq(EvaluationCustomEvaluator::getTenantId, tenantId)
                        .orderByAsc(EvaluationCustomEvaluator::getId))
                .stream().map(this::toCustomView).toList();
    }

    @Transactional
    public CustomEvaluatorView createCustomEvaluator(CustomEvaluatorView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        validateCustom(req);
        EvaluationCustomEvaluator e = new EvaluationCustomEvaluator();
        e.setTenantId(tenantId);
        e.setName(req.name().trim());
        e.setCategory(req.category());
        e.setDescription(req.description());
        e.setRuleType("rule".equals(req.category()) ? req.ruleType() : null);
        e.setParams("rule".equals(req.category()) ? jsonOrNull(req.params()) : null);
        e.setJudgePrompt("llm_judge".equals(req.category()) ? req.judgePrompt() : null);
        e.setStatus(req.status() == null || req.status().isBlank()
                ? "ENABLED" : req.status().trim().toUpperCase());
        if (!"ENABLED".equals(e.getStatus()) && !"DISABLED".equals(e.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法状态（ENABLED/DISABLED）: " + req.status());
        }
        e.setCreatedBy(operator);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        customEvaluatorMapper.insert(e);
        return toCustomView(e);
    }

    @Transactional
    public CustomEvaluatorView updateCustomEvaluator(Long id, CustomEvaluatorView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationCustomEvaluator e = requireCustom(id, tenantId);
        validateCustom(req);
        e.setName(req.name().trim());
        e.setCategory(req.category());
        e.setDescription(req.description());
        e.setRuleType("rule".equals(req.category()) ? req.ruleType() : null);
        e.setParams("rule".equals(req.category()) ? jsonOrNull(req.params()) : null);
        e.setJudgePrompt("llm_judge".equals(req.category()) ? req.judgePrompt() : null);
        e.setStatus(req.status() == null || req.status().isBlank()
                ? "ENABLED" : req.status().trim().toUpperCase());
        if (!"ENABLED".equals(e.getStatus()) && !"DISABLED".equals(e.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法状态（ENABLED/DISABLED）: " + req.status());
        }
        e.setUpdatedAt(Instant.now());
        customEvaluatorMapper.updateById(e);
        return toCustomView(e);
    }

    @Transactional
    public void deleteCustomEvaluator(Long id) {
        Long tenantId = TenantContext.require();
        requireCustom(id, tenantId);
        customEvaluatorMapper.deleteById(id);
    }

    private EvaluationCustomEvaluator requireCustom(Long id, Long tenantId) {
        EvaluationCustomEvaluator e = customEvaluatorMapper.selectById(id);
        if (e == null || !tenantId.equals(e.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "自定义评测器不存在: " + id);
        }
        return e;
    }

    /** 自定义评测器校验：rule 类必须 rule_type + params；llm_judge 类必须 judge_prompt。 */
    private void validateCustom(CustomEvaluatorView.SaveRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测器名称不能为空");
        }
        if (!"rule".equals(req.category()) && !"llm_judge".equals(req.category())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法类别（rule/llm_judge）: " + req.category());
        }
        if ("rule".equals(req.category())) {
            if (req.ruleType() == null || req.ruleType().isBlank() || !List.of(
                    "keyword_contains", "regex_match", "length_between").contains(req.ruleType())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "规则评测器必须指定合法 rule_type（keyword_contains/regex_match/length_between）");
            }
            if (req.params() == null || req.params().isNull()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "规则评测器必须提供规则参数 params");
            }
        } else if (req.judgePrompt() == null || req.judgePrompt().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "LLM-Judge 必须提供 judge_prompt 提示词");
        }
    }

    private CustomEvaluatorView toCustomView(EvaluationCustomEvaluator e) {
        return new CustomEvaluatorView(e.getId(), e.metric(), e.getName(), e.getCategory(), e.getDescription(),
                e.getRuleType(), parse(e.getParams()), e.getJudgePrompt(), e.getStatus(),
                e.getCreatedBy(), e.getCreatedAt());
    }

    private ReportView toReportView(EvaluationReport r) {
        return new ReportView(r.getId(), r.getDatasetId(), r.getName(), r.getTotalCases(),
                r.getTestedCases(), parse(r.getMetrics()), parse(r.getFindings()), parse(r.getSummary()),
                r.getConfidence(), r.getModel(), r.getMode(),
                r.getJudgeRounds(), r.getTraceId(), r.getCreatedBy(), r.getCreatedAt());
    }
}