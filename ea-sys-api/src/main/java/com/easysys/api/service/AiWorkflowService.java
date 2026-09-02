package com.easysys.api.service;

import com.easysys.agent.AgentExecutor;
import com.easysys.agent.AgentOutcome;
import com.easysys.agent.AgentRunConfig;
import com.easysys.agent.WorkflowPlanner;
import com.easysys.api.dto.audience.AudienceResponse;
import com.easysys.api.dto.channel.ChannelConfigView;
import com.easysys.api.dto.template.TemplateView;
import com.easysys.api.dto.workflow.AiGenerateResponse;
import com.easysys.api.dto.workflow.AiToolCallView;
import com.easysys.api.dto.workflow.SaveWorkflowRequest;
import com.easysys.api.dto.workflow.WorkflowEdgeSpec;
import com.easysys.api.dto.workflow.WorkflowNodeSpec;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.engine.dag.DagValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * AI 创建工作流编排（WORKFLOW agent）：
 * <ol>
 *   <li>真实执行租户数据工具（list_channels / search_templates / search_audiences）并记录时间线；</li>
 *   <li>AgentExecutor 跑确定性规划器 WorkflowPlanner（无 I/O 纯解析）→ schema 校验 + 置信度闸门 + 审计；</li>
 *   <li>复用引擎 DagValidator 校验生成的 DAG 结构（validate_dag 工具记录）；</li>
 *   <li>产出「草稿」—— 不落库，前端人工审核后走既有保存/发布/干跑链路。</li>
 * </ol>
 * 工具执行不进 AgentExecutor：规划器保持无 I/O 纯解析，与 LAYER/ROUTER/CHURN 链路零耦合。
 */
@Service
public class AiWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AiWorkflowService.class);

    private final TemplateService templateService;
    private final AudienceService audienceService;
    private final ChannelConfigService channelConfigService;
    private final DagValidator dagValidator;
    private final AgentAuditMapper auditMapper;
    private final ObjectMapper json;

    public AiWorkflowService(TemplateService templateService,
                             AudienceService audienceService,
                             ChannelConfigService channelConfigService,
                             DagValidator dagValidator,
                             AgentAuditMapper auditMapper,
                             ObjectMapper json) {
        this.templateService = templateService;
        this.audienceService = audienceService;
        this.channelConfigService = channelConfigService;
        this.dagValidator = dagValidator;
        this.auditMapper = auditMapper;
        this.json = json;
    }

    /**
     * AI 生成工作流草稿（不落库）。租户/操作人显式传入：
     * 对话式创建（HarnessAgent 工具线程无 TenantContext）与旧接口共用。
     */
    @Transactional
    public AiGenerateResponse generateForTenant(Long tenantId, String prompt, String operator) {
        String prompt0 = prompt.trim();
        List<AiToolCallView> toolCalls = new ArrayList<>();

        // 工具 1：list_channels —— 当前租户通道可用性（脱敏）
        listChannels(tenantId, toolCalls);
        // 工具 2：search_templates —— 启用模板全集（AI 内部按通道/语义匹配）
        List<TemplateView> templates = searchTemplates(toolCalls);
        // 工具 3：search_audiences —— 现有人群（不自动创建，未命中仅提示）
        List<AudienceResponse> audiences = searchAudiences(tenantId, toolCalls);

        // 规划器输入：自带租户上下文快照（仅关键字段，量受控）
        ObjectNode input = json.createObjectNode();
        input.put("prompt", prompt0);
        ArrayNode ts = input.putArray("templates");
        for (TemplateView t : templates) {
            ObjectNode o = ts.addObject();
            o.put("id", t.id());
            o.put("channel", t.channel());
            o.put("name", t.name());
            o.put("content", t.content());
            o.put("status", t.status());
        }
        ArrayNode as = input.putArray("audiences");
        for (AudienceResponse a : audiences) {
            ObjectNode o = as.addObject();
            o.put("id", a.id());
            o.put("name", a.name());
            o.put("rule", a.rule());
        }

        // 工具 4：build_dag —— 确定性规划器（AgentExecutor 承载：结构校验 + 置信度闸门）
        WorkflowPlanner planner = new WorkflowPlanner();
        AgentOutcome outcome = runPlanner(toolCalls, input);
        JsonNode out = outcome.output();
        if (out == null || !out.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "工作流生成失败(确定性兜底也失效): " + outcome.reason());
        }

        List<WorkflowNodeSpec> nodes = nodeSpecs(out.path("nodes"));
        List<WorkflowEdgeSpec> edges = edgeSpecs(out.path("edges"));

        // 工具 5：validate_dag —— 复用引擎结构校验（人工审核前的最后一道自动闸门）
        validateDag(toolCalls, nodes, edges);

        // 审计落库（生成动作，草稿未落库，仅审计 AI 产出）
        persistAudit(tenantId, outcome, operator);

        SaveWorkflowRequest draft = new SaveWorkflowRequest(
                out.path("name").asText(""),
                out.path("description").asText(""),
                nodes, edges);
        return new AiGenerateResponse(draft, toolCalls,
                out.path("planSummary").asText(""),
                out.path("audienceHint"));
    }

    // ---- 工具执行 ----

    /**
     * 公开查询方法（对话式创建框架工具复用；返回全量行，不写时间线审计）。
     * 注意：模板为全租户共享语义，与 listChannels/listAudiences 不同。
     */
    public List<ChannelConfigView> listChannelsFor(Long tenantId) {
        return listChannels(tenantId, new ArrayList<>());
    }

    public List<TemplateView> searchTemplatesFor() {
        return searchTemplates(new ArrayList<>());
    }

    public List<AudienceResponse> searchAudiencesFor(Long tenantId) {
        return searchAudiences(tenantId, new ArrayList<>());
    }

    private List<ChannelConfigView> listChannels(Long tenantId, List<AiToolCallView> calls) {
        long start = System.currentTimeMillis();
        try {
            List<ChannelConfigView> rows = channelConfigService.list(tenantId, null);
            ArrayNode summary = json.createArrayNode();
            for (ChannelConfigView v : rows) {
                ObjectNode o = summary.addObject();
                o.put("channel", v.channel());
                o.put("enabled", Boolean.TRUE.equals(v.enabled()));
            }
            calls.add(view("list_channels", emptyArgs(),
                    summary.isEmpty() ? json.createObjectNode().put("note", "租户无通道配置") : summary,
                    "SUCCESS", start));
            return rows;
        } catch (Exception e) {
            return failed(calls, "list_channels", e, start, List.of());
        }
    }

    private List<TemplateView> searchTemplates(List<AiToolCallView> calls) {
        long start = System.currentTimeMillis();
        ObjectNode args = json.createObjectNode();
        args.put("keyword", "enabled 模板经 AI 语义匹配");
        try {
            List<TemplateView> rows = templateService.list();
            ArrayNode summary = json.createArrayNode();
            for (TemplateView t : rows) {
                ObjectNode o = summary.addObject();
                o.put("id", t.id());
                o.put("channel", t.channel());
                o.put("name", t.name());
            }
            calls.add(view("search_templates", args, summary, "SUCCESS", start));
            return rows;
        } catch (Exception e) {
            return failed(calls, "search_templates", e, start, List.of());
        }
    }

    private List<AudienceResponse> searchAudiences(Long tenantId, List<AiToolCallView> calls) {
        long start = System.currentTimeMillis();
        ObjectNode args = json.createObjectNode();
        args.put("page", 1);
        args.put("size", 100);
        try {
            List<AudienceResponse> rows = audienceService.list(tenantId, 1, 100).records();
            ArrayNode summary = json.createArrayNode();
            for (AudienceResponse a : rows) {
                ObjectNode o = summary.addObject();
                o.put("id", a.id());
                o.put("name", a.name());
            }
            calls.add(view("search_audiences", args, summary, "SUCCESS", start));
            return rows;
        } catch (Exception e) {
            return failed(calls, "search_audiences", e, start, List.of());
        }
    }

    private AgentOutcome runPlanner(List<AiToolCallView> calls, JsonNode input) {
        long start = System.currentTimeMillis();
        try {
            AgentOutcome outcome = AgentExecutor.run(new WorkflowPlanner(), new WorkflowPlanner(),
                    "workflow_generate", input, AgentRunConfig.defaults());
            ObjectNode args = json.createObjectNode();
            args.put("prompt", input.path("prompt").asText("").substring(0,
                    Math.min(input.path("prompt").asText("").length(), 80)));
            ObjectNode result = json.createObjectNode();
            JsonNode out = outcome.output();
            if (out != null && out.isObject()) {
                result.put("nodes", out.path("nodes").size());
                result.put("edges", out.path("edges").size());
                result.put("planSummary", out.path("planSummary").asText(""));
                result.put("confidence", out.path("confidence").asDouble(1.0));
            }
            calls.add(view("build_dag", args, result,
                    outcome.output() == null ? "FAILED" : "SUCCESS", start));
            return outcome;
        } catch (Exception e) {
            return failed(calls, "build_dag", e, start, null);
        }
    }

    private void validateDag(List<AiToolCallView> calls, List<WorkflowNodeSpec> nodes, List<WorkflowEdgeSpec> edges) {
        long start = System.currentTimeMillis();
        List<DagValidator.NodeDef> ndefs = nodes.stream()
                .map(n -> new DagValidator.NodeDef(n.key(), n.type(), n.config()))
                .toList();
        List<DagValidator.EdgeDef> edefs = edges.stream()
                .map(e -> new DagValidator.EdgeDef(e.source(), e.target(), e.condition()))
                .toList();
        List<String> errors = new ArrayList<>(dagValidator.validate(ndefs, edefs).errors());
        ObjectNode args = json.createObjectNode();
        args.put("nodes", ndefs.size());
        args.put("edges", edefs.size());
        ArrayNode result = json.createArrayNode();
        for (String e : errors) {
            result.add(e);
        }
        calls.add(view("validate_dag", args, result,
                errors.isEmpty() ? "SUCCESS" : "WARN", start));
    }

    // ---- 组装助手 ----

    private List<WorkflowNodeSpec> nodeSpecs(JsonNode nodes) {
        List<WorkflowNodeSpec> out = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            return out;
        }
        for (JsonNode n : nodes) {
            out.add(new WorkflowNodeSpec(
                    n.path("key").asText(),
                    n.path("type").asText(),
                    n.has("name") && !n.path("name").isNull() ? n.path("name").asText() : null,
                    n.has("config") ? n.path("config") : null,
                    null));
        }
        return out;
    }

    private List<WorkflowEdgeSpec> edgeSpecs(JsonNode edges) {
        List<WorkflowEdgeSpec> out = new ArrayList<>();
        if (edges == null || !edges.isArray()) {
            return out;
        }
        for (JsonNode e : edges) {
            out.add(new WorkflowEdgeSpec(
                    e.path("source").asText(),
                    e.path("target").asText(),
                    e.has("condition") ? e.path("condition") : null));
        }
        return out;
    }

    private AiToolCallView view(String name, ObjectNode args, JsonNode result, String status, long start) {
        return new AiToolCallView(name, args, result, status, System.currentTimeMillis() - start);
    }

    private <T> T failed(List<AiToolCallView> calls, String name, Exception e, long start, T fallback) {
        log.warn("[ai-generate] 工具 {} 执行失败,降级为缺省: {}", name, e.toString());
        ObjectNode result = json.createObjectNode();
        result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        calls.add(view(name, emptyArgs(), result, "FAILED", start));
        return fallback;
    }

    private ObjectNode emptyArgs() {
        return json.createObjectNode();
    }

    /** audit_log 持久化（AgentOutcome → AgentAudit，与 StrategyService 同构）。 */
    private void persistAudit(Long tenantId, AgentOutcome outcome, String operator) {
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

    private String writeOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(n);
        } catch (Exception e) {
            return n.toString();
        }
    }
}