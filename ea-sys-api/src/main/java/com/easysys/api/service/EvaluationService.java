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
import com.easysys.api.dto.evaluation.MetricCatalogView;
import com.easysys.api.dto.evaluation.ReportCompareView;
import com.easysys.api.dto.evaluation.ReportView;
import com.easysys.api.dto.evaluation.TaskDetailView;
import com.easysys.api.dto.evaluation.TaskView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.EvaluationCase;
import com.easysys.api.entity.EvaluationCustomEvaluator;
import com.easysys.api.entity.EvaluationDataset;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.entity.EvaluationTask;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.EvaluationCaseMapper;
import com.easysys.api.mapper.EvaluationCustomEvaluatorMapper;
import com.easysys.api.mapper.EvaluationDatasetMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
import com.easysys.api.mapper.EvaluationTaskMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final EvaluationTaskMapper taskMapper;
    private final LlmJudgeScorer judgeScorer;
    private final HarnessAgent evaluationAgent;
    private final HarnessAgent assistantAgent;
    private final HarnessAgent workflowDialogueAgent;
    private final AgentLlmProperties llm;
    private final ObjectMapper json;
    private final ExecutorService evaluationTaskExecutor;

    /** 取消中的任务标记（RUNNING/CANCELING 由请求线程写入，执行线程逐样本检查点读取）。 */
    private final Map<Long, Boolean> cancelRequested = new ConcurrentHashMap<>();

    /** LLM-Judge 判分并行池：8 并发足够（判分互相独立，串行 36 次判分曾拖慢全量 run 至 2 分钟）。 */
    private static final ExecutorService JUDGE_POOL = Executors.newFixedThreadPool(8);

    /** 任务进度上报粒度：每 5 例落一次 tested_cases/progress_pct。 */
    private static final int PROGRESS_EVERY = 5;

    public EvaluationService(EvaluationDatasetMapper datasetMapper, EvaluationCaseMapper caseMapper,
                             EvaluationReportMapper reportMapper, AgentAuditMapper auditMapper,
                             EvaluationCustomEvaluatorMapper customEvaluatorMapper,
                             EvaluationTaskMapper taskMapper,
                             LlmJudgeScorer judgeScorer,
                             HarnessAgent evaluationAgent,
                             @Qualifier("assistantAgent") HarnessAgent assistantAgent,
                             @Qualifier("workflowDialogueAgent") HarnessAgent workflowDialogueAgent,
                             AgentLlmProperties llm, ObjectMapper json,
                             @Qualifier("evaluationTaskExecutor") ExecutorService evaluationTaskExecutor) {
        this.datasetMapper = datasetMapper;
        this.caseMapper = caseMapper;
        this.reportMapper = reportMapper;
        this.auditMapper = auditMapper;
        this.customEvaluatorMapper = customEvaluatorMapper;
        this.taskMapper = taskMapper;
        this.judgeScorer = judgeScorer;
        this.evaluationAgent = evaluationAgent;
        this.assistantAgent = assistantAgent;
        this.workflowDialogueAgent = workflowDialogueAgent;
        this.llm = llm;
        this.json = json;
        this.evaluationTaskExecutor = evaluationTaskExecutor;
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
        writeAudit(tenantId, "EVALUATION_DATASET_CREATE", auditSummary(
                "datasetId", d.getId(), "name", d.getName(), "mode", d.getMode()),
                null, operator);
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
        writeAudit(tenantId, "EVALUATION_DATASET_UPDATE", auditSummary(
                "datasetId", d.getId(), "name", d.getName(), "mode", d.getMode()),
                null, operator);
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
        writeAudit(tenantId, "EVALUATION_DATASET_DELETE", auditSummary(
                "datasetId", d.getId(), "name", d.getName()), null, null);
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
        writeAudit(tenantId, "EVALUATION_CASE_CREATE", auditSummary(
                "datasetId", datasetId, "caseId", c.getId(), "seq", c.getSeq()),
                null, operator);
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
        writeAudit(tenantId, "EVALUATION_CASE_UPDATE", auditSummary(
                "datasetId", c.getDatasetId(), "caseId", c.getId(), "seq", c.getSeq()),
                null, operator);
        return toCaseView(c);
    }

    @Transactional
    public void deleteCase(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationCase c = requireCase(id, tenantId);
        caseMapper.deleteById(c.getId());
        writeAudit(tenantId, "EVALUATION_CASE_DELETE", auditSummary(
                "datasetId", c.getDatasetId(), "caseId", c.getId(), "seq", c.getSeq()),
                null, null);
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
        writeAudit(tenantId, "EVALUATION_IMPORT", auditSummary(
                "datasetId", datasetId, "imported", imported, "skipped", skipped),
                null, operator);
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
        List<EvaluationCase> cases = loadCases(d.getId());
        if (cases.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集无可评测用例，请先添加用例");
        }

        // 判分轮次（1-5 多次取均值）+ 运行追踪 ID（驾驶舱 LLM 调用联动）
        int judgeRounds = clampRounds(req.judgeRounds());
        String traceId = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 租户自定义评测器（逻辑删除由 MyBatis-Plus 自动过滤）
        Map<Long, EvaluationCustomEvaluator> customsById = loadCustoms(tenantId);

        ObjectNode input = buildInput(d, cases, judgeRounds, traceId, tenantId, operator,
                req.evaluators(), customsById);

        // LLM 启用时对选中 LLM-Judge 评测器逐 case 真实打分（0-100）注入 judge_scores；
        // 停用/失败/无 apiKey → 不注入，EvaluationModel 走确定性近似降级（不整轮失败）。
        // 逐样本判分明细（score+reason）仅供异步任务样本落库消费，同步 run 忽略返回值。
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
        return persistReport(tenantId, d, cases, judgeRounds, traceId, operator, outcome.output(),
                BigDecimal.valueOf(outcome.audit().confidence() == null ? 1.0 : outcome.audit().confidence()),
                outcome.audit().model() == null ? "deterministic" : outcome.audit().model());
    }

    /** 用例列表（seq 升序，与 run 全量判分顺序一致）。 */
    private List<EvaluationCase> loadCases(Long datasetId) {
        return caseMapper.selectList(new LambdaQueryWrapper<EvaluationCase>()
                .eq(EvaluationCase::getDatasetId, datasetId)
                .orderByAsc(EvaluationCase::getSeq)
                .orderByAsc(EvaluationCase::getId));
    }

    /** 租户自定义评测器全集（MyBatis-Plus 自动过滤逻辑删除）。 */
    private Map<Long, EvaluationCustomEvaluator> loadCustoms(Long tenantId) {
        Map<Long, EvaluationCustomEvaluator> customsById = new HashMap<>();
        for (EvaluationCustomEvaluator ce : customEvaluatorMapper.selectList(
                new LambdaQueryWrapper<EvaluationCustomEvaluator>()
                        .eq(EvaluationCustomEvaluator::getTenantId, tenantId))) {
            customsById.put(ce.getId(), ce);
        }
        return customsById;
    }

    /** 判分轮次 1-5（多次取均值）。 */
    private static int clampRounds(Integer rounds) {
        return rounds == null ? 1 : Math.max(1, Math.min(rounds, 5));
    }

    /** 组装引擎判分输入：mode/自定义评测器定义/用例（execute 逐用例真实运行被测智能体）/评测器选中集。 */
    private ObjectNode buildInput(EvaluationDataset d, List<EvaluationCase> cases, int judgeRounds,
                                  String traceId, Long tenantId, String operator,
                                  List<String> selectedMetrics,
                                  Map<Long, EvaluationCustomEvaluator> customsById) {
        ReActAgent subject = "execute".equals(d.getMode())
                ? subjectDelegate(d.getAgentType()) : null;
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
        if (selectedMetrics != null && !selectedMetrics.isEmpty()) {
            for (String metric : selectedMetrics) {
                EvaluationCustomEvaluator custom = customsById.get(customIdOf(metric));
                if (EvaluationModel.ALL_METRICS.contains(metric) || custom != null) {
                    ObjectNode e = evals.addObject();
                    e.put("metric", metric);
                    e.put("category", custom != null ? custom.getCategory()
                            : (EvaluationModel.RULE_METRICS.contains(metric) ? "rule" : "llm_judge"));
                }
            }
        }
        return input;
    }

    /** 判分输入组装 → 引擎单用例输入（保留自定义评测器定义/评测器集，仅保留一个用例）。 */
    private ObjectNode singleCaseInput(ObjectNode input, JsonNode caseNode) {
        ObjectNode out = json.createObjectNode();
        out.put("scope", input.path("scope").asText("llm_call"));
        out.put("mode", input.path("mode").asText("openjudge"));
        out.put("llm_enabled", input.path("llm_enabled").asBoolean(false));
        ArrayNode customs = out.putArray("custom_evaluators");
        if (input.path("custom_evaluators").isArray()) {
            customs.addAll((ArrayNode) input.path("custom_evaluators").deepCopy());
        }
        ArrayNode single = out.putArray("cases");
        single.add(caseNode.deepCopy()); // 已含 judge_scores/custom 判定注入
        ArrayNode evals = out.putArray("evaluators");
        if (input.path("evaluators").isArray()) {
            evals.addAll((ArrayNode) input.path("evaluators").deepCopy());
        }
        return out;
    }

    /** 任务实际评测器集（与引擎缺省口径一致：未选中 = 全量 15 个内置）。 */
    private List<JsonNode> effectiveSpecs(ObjectNode input) {
        List<JsonNode> specs = new ArrayList<>();
        ArrayNode evals = input.path("evaluators").isArray()
                ? (ArrayNode) input.path("evaluators") : null;
        if (evals == null || evals.isEmpty()) {
            for (String m : EvaluationModel.ALL_METRICS) {
                specs.add(json.createObjectNode()
                        .put("metric", m)
                        .put("category", EvaluationModel.RULE_METRICS.contains(m)
                                ? "rule" : "llm_judge"));
            }
        } else {
            evals.forEach(specs::add);
        }
        return specs;
    }

    /** 报告落库（run 与异步任务共用；spec 单次入事务或单条 insert 自动提交）。 */
    private ReportView persistReport(Long tenantId, EvaluationDataset d, List<EvaluationCase> cases,
                                     int judgeRounds, String traceId, String operator, JsonNode output,
                                     BigDecimal confidence, String model) {
        EvaluationReport r = new EvaluationReport();
        r.setTenantId(tenantId);
        r.setDatasetId(d.getId());
        r.setName(d.getName());
        r.setTotalCases(cases.size());
        r.setTestedCases(output.path("tested_cases").asInt(cases.size()));
        r.setMetrics(writeOrNull(output.path("metrics")));
        r.setFindings(writeOrNull(output.path("findings")));
        r.setSummary(writeOrNull(output.path("summary")));
        r.setConfidence(confidence);
        r.setModel(model);
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
        return toReportView(requireReport(id, tenantId));
    }

    @Transactional
    public void deleteReport(Long id) {
        Long tenantId = TenantContext.require();
        reportMapper.deleteById(requireReport(id, tenantId).getId());
    }

    private EvaluationReport requireReport(Long id, Long tenantId) {
        EvaluationReport r = reportMapper.selectById(id);
        if (r == null || !tenantId.equals(r.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测报告不存在: " + id);
        }
        return r;
    }

    // ---------- 评测任务（异步状态机） ----------

    /**
     * 创建异步评测任务：参数快照入 evaluation_task.params，PENDING 落库后立即投递执行线程，
     * 202 返回任务视图。不做 @Transactional——单条 insert 自动提交即可，异步线程需读到已提交行。
     */
    public TaskView createTask(EvaluationRunRequest req, String operator) {
        Long tenantId = TenantContext.require();
        if (req.datasetId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "datasetId 不能为空");
        }
        EvaluationDataset d = requireDataset(req.datasetId(), tenantId);

        ObjectNode params = json.createObjectNode();
        params.put("datasetId", d.getId());
        params.put("name", d.getName());
        params.put("mode", d.getMode());
        params.put("judgeRounds", clampRounds(req.judgeRounds()));
        ArrayNode evalArr = params.putArray("evaluators");
        if (req.evaluators() != null) {
            req.evaluators().forEach(evalArr::add);
        }
        String traceId = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        params.put("traceId", traceId);

        EvaluationTask t = new EvaluationTask();
        t.setTenantId(tenantId);
        t.setName(d.getName());
        t.setDatasetId(d.getId());
        t.setStatus("PENDING");
        t.setTotalCases(0);
        t.setTestedCases(0);
        t.setProgressPct(BigDecimal.ZERO);
        t.setParams(writeOrNull(params));
        t.setCreatedBy(operator);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        taskMapper.insert(t);

        writeAudit(tenantId, "EVALUATION_TASK_CREATE", auditSummary(
                "taskId", t.getId(), "datasetId", d.getId(), "name", d.getName(),
                "mode", d.getMode(), "traceId", traceId),
                null, operator);

        Long taskId = t.getId();
        evaluationTaskExecutor.submit(() -> runTaskAsync(taskId, tenantId, operator));
        return toTaskView(t);
    }

    /** 任务列表（created_at 倒序）。 */
    public List<TaskView> listTasks() {
        Long tenantId = TenantContext.require();
        return taskMapper.selectList(new LambdaQueryWrapper<EvaluationTask>()
                        .eq(EvaluationTask::getTenantId, tenantId)
                        .orderByDesc(EvaluationTask::getCreatedAt))
                .stream().map(this::toTaskView).toList();
    }

    /** 任务详情：任务行 + 逐样本指标聚合（均值/通过数/适用数/多轮离散度）。 */
    public TaskDetailView getTask(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationTask t = requireTask(id, tenantId);
        return new TaskDetailView(toTaskView(t), breakdownMetrics(t));
    }

    /**
     * 取消任务：PENDING → 直接 CANCELED；RUNNING → CANCELING + 执行线程检查点裁决；
     * 终态（COMPLETED/FAILED/CANCELED）→ 400。取消竞争由 SQL 状态前置 + 逐样本检查点闭环。
     */
    public TaskView cancelTask(Long id, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationTask t = requireTask(id, tenantId);
        String status = t.getStatus();
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "任务已结束（" + status + "），无法取消");
        }
        if ("PENDING".equals(status)) {
            taskMapper.markCanceled(id, tenantId, "任务已取消");
        } else {
            taskMapper.markCanceling(id, tenantId);
            cancelRequested.put(id, Boolean.TRUE);
        }
        writeAudit(tenantId, "EVALUATION_TASK_CANCEL", auditSummary(
                "taskId", id, "datasetId", t.getDatasetId(), "name", t.getName(),
                "fromStatus", status),
                null, operator);
        return toTaskView(taskMapper.selectById(id));
    }

    /**
     * 异步任务执行主线：租户上下文隔离 → 前置校验（FAILED）→ PENDING→RUNNING 抢占 →
     * LLM 判分注入 → 逐用例确定性 plan()（不经 harness 15s LLM 额度，数学等价，见 docs/99 实施记录）→
     * 进度上报 → 聚合权威报告 → run 审计一次 → 报告落库 → COMPLETED。
     */
    private void runTaskAsync(Long taskId, Long tenantId, String operator) {
        TenantContext.set(new TenantInfo(tenantId));
        try {
            EvaluationTask t = taskMapper.selectById(taskId);
            if (t == null) {
                return;
            }
            ObjectNode params = parse(t.getParams()) instanceof ObjectNode p ? p : json.createObjectNode();
            int judgeRounds = params.path("judgeRounds").asInt(1);
            List<String> selected = new ArrayList<>();
            params.path("evaluators").forEach(e -> selected.add(e.asText()));
            String traceId = params.path("traceId").asText("");

            EvaluationDataset d = datasetMapper.selectById(t.getDatasetId());
            List<EvaluationCase> cases = loadCases(t.getDatasetId());
            if (d == null || "DISABLED".equals(d.getStatus())) {
                taskMapper.markFailed(taskId, tenantId, "数据集不存在或已停用，任务失败");
                return;
            }
            if (cases.isEmpty()) {
                taskMapper.markFailed(taskId, tenantId, "数据集无可评测用例，请先添加用例");
                return;
            }
            if (!"openjudge".equals(d.getMode()) && !"execute".equals(d.getMode())) {
                taskMapper.markFailed(taskId, tenantId, "未知评测模式: " + d.getMode());
                return;
            }
            if (taskMapper.claimRunning(taskId, tenantId, cases.size()) == 0) {
                return; // PENDING→CANCELED 取消竞争失败，执行线程直接退出
            }

            Map<Long, EvaluationCustomEvaluator> customsById = loadCustoms(tenantId);
            ObjectNode input = buildInput(d, cases, judgeRounds, traceId, tenantId, operator,
                    selected.isEmpty() ? null : selected, customsById);
            List<JsonNode> specs = effectiveSpecs(input);
            if (isCanceled(taskId) || "CANCELING".equals(taskMapper.selectById(taskId).getStatus())) {
                taskMapper.markCanceled(taskId, tenantId, "任务已取消");
                return;
            }
            List<Map<String, LlmJudgeScorer.JudgeDetail>> detailsByCase
                    = injectJudgeScores(input, selected.isEmpty() ? null : selected,
                    judgeRounds, tenantId, traceId, customsById);
            if (isCanceled(taskId)) {
                taskMapper.markCanceled(taskId, tenantId, "任务已取消");
                return;
            }

            // 逐用例确定性评测 + 逐样本落库（含 LLM 判分 reason/轮次离散度）
            ArrayNode samples = json.createArrayNode();
            List<JsonNode> perCaseOutputs = new ArrayList<>();
            int idx = 0;
            JsonNode caseArr = input.path("cases");
            for (JsonNode cn : caseArr) {
                if (isCanceled(taskId)) {
                    taskMapper.markCanceled(taskId, tenantId, "任务已取消");
                    return;
                }
                JsonNode out = new EvaluationModel().plan(singleCaseInput(input, cn));
                perCaseOutputs.add(out);
                samples.add(buildSample(cn, out, detailsByCase.get(idx), d.getMode()));
                idx++;
                if (idx % PROGRESS_EVERY == 0) {
                    taskMapper.markProgress(taskId, tenantId, idx, progressPct(idx, caseArr.size()));
                }
            }
            if (isCanceled(taskId)) {
                taskMapper.markCanceled(taskId, tenantId, "任务已取消");
                return;
            }
            taskMapper.markProgress(taskId, tenantId, caseArr.size(),
                    progressPct(caseArr.size(), caseArr.size()));

            JsonNode output = aggregateOutput(perCaseOutputs, specs, caseArr.size(), d.getMode());
            writeAudit(tenantId, "evaluation_run", "SUCCESS",
                    writeOrNull(input), writeOrNull(output), operator);
            ReportView report = persistReport(tenantId, d, cases, judgeRounds, traceId, operator,
                    output, BigDecimal.ONE, "deterministic");
            if (taskMapper.markCompleted(taskId, tenantId, report.id(), writeOrNull(samples)) == 0) {
                // 取消已抢先置 CANCELING：回滚刚落库的报告，闭环为 CANCELED
                reportMapper.deleteById(report.id());
                taskMapper.markCanceled(taskId, tenantId, "任务已取消");
            }
        } catch (Exception e) {
            log.error("评测任务 {} 执行失败: {}", taskId, e.getMessage(), e);
            taskMapper.markFailed(taskId, tenantId, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            TenantContext.clear();
            cancelRequested.remove(taskId);
        }
    }

    private boolean isCanceled(Long taskId) {
        return Boolean.TRUE.equals(cancelRequested.get(taskId));
    }

    private EvaluationTask requireTask(Long id, Long tenantId) {
        EvaluationTask t = taskMapper.selectById(id);
        if (t == null || !tenantId.equals(t.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测任务不存在: " + id);
        }
        return t;
    }

    private TaskView toTaskView(EvaluationTask t) {
        return new TaskView(t.getId(), t.getName(), t.getDatasetId(), t.getStatus(),
                t.getTotalCases() == null ? 0 : t.getTotalCases(),
                t.getTestedCases() == null ? 0 : t.getTestedCases(),
                t.getProgressPct() == null ? BigDecimal.ZERO : t.getProgressPct(),
                t.getErrorMessage(), t.getReportId(), parse(t.getSampleResults()),
                t.getCreatedBy(), t.getCreatedAt(), t.getUpdatedAt());
    }

    /** 进度百分比（0-100，2 位小数）。 */
    private static BigDecimal progressPct(int tested, int total) {
        double pct = total == 0 ? 0.0 : Math.round(tested * 10_000.0 / total) / 100.0;
        return BigDecimal.valueOf(pct);
    }

    /**
     * 逐样本落库结构：{seq, question, actual_response, mode, metrics:[{metric, category,
     * score(0-1), passed(>=0.8), reason?, round_scores?}]}。reason/round_scores 仅 LLM-Judge
     * 且有真实判分明细时写入（规则类恒缺），LLM 未启用时为空属合法。
     */
    private ObjectNode buildSample(JsonNode cn, JsonNode out,
                                   Map<String, LlmJudgeScorer.JudgeDetail> details, String mode) {
        ObjectNode sample = json.createObjectNode();
        sample.put("seq", cn.path("seq").asInt());
        sample.put("question", truncate(cn.path("question").asText(""), 200));
        sample.put("actual_response", truncate(cn.path("actual_response").asText(""), 500));
        sample.put("mode", mode);
        ArrayNode metrics = sample.putArray("metrics");
        for (JsonNode m : out.path("metrics")) {
            String metric = m.path("metric").asText();
            double score = m.path("avg_score").asDouble();
            ObjectNode row = metrics.addObject();
            row.put("metric", metric);
            row.put("category", m.path("category").asText(""));
            row.put("score", round4(score));
            row.put("passed", score >= 0.8);
            LlmJudgeScorer.JudgeDetail detail = details == null ? null : details.get(metric);
            if (detail != null) {
                String reason = detail.rounds().stream()
                        .map(LlmJudgeScorer.JudgeRound::reason)
                        .filter(Objects::nonNull)
                        .filter(r -> !r.isBlank())
                        .findFirst().orElse(null);
                if (reason != null) {
                    row.put("reason", truncate(reason, 500));
                }
                ArrayNode rounds = row.putArray("round_scores");
                detail.rounds().forEach(r -> rounds.add(Math.round(r.score())));
            }
        }
        return sample;
    }

    /**
     * 聚合权威报告：逐用例 per-case 输出按评测器规格聚合均值/通过数/适用数与分级发现，
     * 与引擎批量 build() 口径完全一致（纯函数、同 selected 顺序、同阈值 0.8/0.6）。
     */
    private ObjectNode aggregateOutput(List<JsonNode> perCaseOutputs, List<JsonNode> specs,
                                       int totalCases, String inMode) {
        ObjectNode out = json.createObjectNode();
        out.put("report_type", "evaluation_report");
        out.put("scope", "llm_call");
        out.put("mode", inMode);
        out.put("tested_cases", totalCases);
        ArrayNode metrics = out.putArray("metrics");
        ArrayNode findings = out.putArray("findings");
        for (JsonNode spec : specs) {
            String metric = spec.path("metric").asText();
            String category = spec.path("category").asText("");
            List<Double> scores = new ArrayList<>();
            for (JsonNode po : perCaseOutputs) {
                for (JsonNode m : po.path("metrics")) {
                    if (metric.equals(m.path("metric").asText()) && m.path("avg_score").isNumber()) {
                        scores.add(m.path("avg_score").asDouble());
                    }
                }
            }
            if (scores.isEmpty()) {
                findings.add(finding("INFO", metric,
                        "评测器 " + metric + " 无适用用例（数据缺失），未纳入均值统计",
                        "补充带 expected 基准的用例后重跑"));
                continue;
            }
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            int passed = (int) scores.stream().filter(s -> s >= 0.8).count();
            ObjectNode row = metrics.addObject();
            row.put("metric", metric);
            row.put("category", category);
            row.put("avg_score", round4(avg));
            row.put("passed_count", passed);
            row.put("applicable_count", scores.size());
            if (avg < 0.6) {
                findings.add(finding("BLOCKED", metric,
                        "评测器 " + metric + " 均值 " + pct(avg)
                                + "（" + passed + "/" + scores.size() + " 例通过，阈值 80%）",
                        "优先修复该维度输出质量，追平评测器基线后再发布上线"));
            } else if (avg < 0.8) {
                findings.add(finding("WARNING", metric,
                        "评测器 " + metric + " 均值 " + pct(avg)
                                + "（" + passed + "/" + scores.size() + " 例通过，阈值 80%）",
                        "关注未通过用例（score<0.8），优化提示词/工具调用或补充期望答案"));
            }
        }
        double raw = metrics.isEmpty() ? 0.0
                : metricsSumOf(metrics) / metrics.size();
        double summaryScore = Math.round(raw * 1000) / 10.0;
        String verdict = summaryScore >= 80 ? "PASS" : (summaryScore >= 60 ? "WARN" : "FAIL");
        ObjectNode summary = out.putObject("summary");
        summary.put("score", summaryScore);
        summary.put("verdict", verdict);
        out.put("strategy_version", "rule");
        out.put("confidence", 1.0);
        out.put("generated_at", Instant.now().toString());
        return out;
    }

    private static double metricsSumOf(ArrayNode metrics) {
        double sum = 0;
        for (JsonNode m : metrics) {
            sum += m.path("avg_score").asDouble();
        }
        return sum;
    }

    private ObjectNode finding(String level, String dimension, String detail, String suggestion) {
        ObjectNode f = json.createObjectNode();
        f.put("level", level);
        f.put("dimension", dimension);
        f.put("detail", detail);
        f.put("suggestion", suggestion);
        return f;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 任务详情 breakdown：从 sample_results 读回聚合（LangSmith 风格）——
     * 平均分（0-1）/通过数/适用数，LLM-Judge 多轮判分的 stddev/mads 跨样本取轮内离散度均值。
     */
    private JsonNode breakdownMetrics(EvaluationTask t) {
        ArrayNode rows = json.createArrayNode();
        JsonNode samples = parse(t.getSampleResults());
        if (samples == null || !samples.isArray()) {
            return rows;
        }
        Map<String, MetricAgg> agg = new LinkedHashMap<>();
        for (JsonNode s : samples) {
            for (JsonNode m : s.path("metrics")) {
                String metric = m.path("metric").asText();
                MetricAgg a = agg.computeIfAbsent(metric, k -> new MetricAgg(m.path("category").asText("")));
                a.scores.add(m.path("score").asDouble());
                if (m.path("round_scores").isArray() && m.path("round_scores").size() > 0) {
                    List<Double> rounds = new ArrayList<>();
                    m.path("round_scores").forEach(r -> rounds.add(r.asDouble()));
                    a.stddevs.add(popStddev(rounds) / 100.0);
                    a.mads.add(mad(rounds) / 100.0);
                }
            }
        }
        for (Map.Entry<String, MetricAgg> e : agg.entrySet()) {
            MetricAgg a = e.getValue();
            ObjectNode row = rows.addObject();
            row.put("metric", e.getKey());
            row.put("category", a.category);
            row.put("avgScore", round4(meanOf(a.scores)));
            row.put("passedCount", (int) a.scores.stream().filter(s -> s >= 0.8).count());
            row.put("applicableCount", a.scores.size());
            if (a.stddevs.isEmpty()) {
                row.putNull("stddev");
                row.putNull("mads");
            } else {
                row.put("stddev", round4(meanOf(a.stddevs)));
                row.put("mads", round4(meanOf(a.mads)));
            }
        }
        return rows;
    }

    private static double meanOf(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** 总体标准差（轮内离散度，0-100 域）。 */
    private static double popStddev(List<Double> xs) {
        double mean = meanOf(xs);
        return Math.sqrt(xs.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0));
    }

    /** 平均绝对偏差（轮内离散度，0-100 域）。 */
    private static double mad(List<Double> xs) {
        double mean = meanOf(xs);
        return xs.stream().mapToDouble(x -> Math.abs(x - mean)).average().orElse(0);
    }

    private static final class MetricAgg {
        final String category;
        final List<Double> scores = new ArrayList<>();
        final List<Double> stddevs = new ArrayList<>();
        final List<Double> mads = new ArrayList<>();

        MetricAgg(String category) {
            this.category = category;
        }
    }

    // ---------- 报告对比（H4） ----------

    /** 报告对比：current 与 baseline 按 metric 对齐，delta=current-baseline，缺项 null。 */
    public ReportCompareView compareReports(Long currentId, Long baselineId) {
        Long tenantId = TenantContext.require();
        if (baselineId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "compare 需指定 baseline 报告 ID");
        }
        EvaluationReport current = requireReport(currentId, tenantId);
        EvaluationReport baseline = requireReport(baselineId, tenantId);
        Map<String, CompareSlot> byMetric = new LinkedHashMap<>();
        fillSlots(byMetric, current, true);
        fillSlots(byMetric, baseline, false);
        List<ReportCompareView.CompareMetric> rows = new ArrayList<>();
        for (CompareSlot slot : byMetric.values()) {
            rows.add(new ReportCompareView.CompareMetric(slot.metric, slot.category,
                    slot.current, slot.baseline,
                    slot.current != null && slot.baseline != null
                            ? round4(slot.current - slot.baseline) : null,
                    "higher_is_better"));
        }
        return new ReportCompareView(
                new ReportCompareView.ReportRef(baseline.getId(), baseline.getCreatedAt(), baseline.getName()),
                new ReportCompareView.ReportRef(current.getId(), current.getCreatedAt(), current.getName()),
                rows);
    }

    private void fillSlots(Map<String, CompareSlot> byMetric,
                           EvaluationReport r, boolean isCurrent) {
        JsonNode metrics = parse(r.getMetrics());
        if (metrics == null || !metrics.isArray()) {
            return;
        }
        for (JsonNode m : metrics) {
            String metric = m.path("metric").asText();
            if (metric.isEmpty() || !m.path("avg_score").isNumber()) {
                continue;
            }
            CompareSlot slot = byMetric.computeIfAbsent(metric,
                    k -> new CompareSlot(metric, m.path("category").asText("")));
            if (isCurrent) {
                slot.current = m.path("avg_score").asDouble();
            } else {
                slot.baseline = m.path("avg_score").asDouble();
            }
        }
    }

    /** 对比聚合槽（current 报告先行，保序）。 */
    private static final class CompareSlot {
        final String metric;
        final String category;
        Double current;
        Double baseline;

        CompareSlot(String metric, String category) {
            this.metric = metric;
            this.category = category;
        }
    }

    // ---------- 评测目录（H6） ----------

    /** 内置指标静态元数据 + 启用的自定义评测器；均 higherIsBetter / 通过线 0.8。 */
    public List<MetricCatalogView> catalog() {
        Long tenantId = TenantContext.require();
        List<MetricCatalogView> list = new ArrayList<>();
        for (String metric : EvaluationModel.ALL_METRICS) {
            list.add(new MetricCatalogView(metric,
                    EvaluationModel.RULE_METRICS.contains(metric) ? "rule" : "llm_judge",
                    true, 0.8));
        }
        for (EvaluationCustomEvaluator ce : customEvaluatorMapper.selectList(
                new LambdaQueryWrapper<EvaluationCustomEvaluator>()
                        .eq(EvaluationCustomEvaluator::getTenantId, tenantId)
                        .eq(EvaluationCustomEvaluator::getStatus, "ENABLED")
                        .orderByAsc(EvaluationCustomEvaluator::getId))) {
            list.add(new MetricCatalogView(ce.metric(), ce.getCategory(), true, 0.8));
        }
        return list;
    }

    // ---------- 内部 ----------

    /**
     * CRUD/任务操作审计（默认 status=SUCCESS）：直构 AgentAudit（agentType=EVALUATION、
     * 确定性 rule 口径），独立 try/catch 吞异常不阻断业务。异步任务内只写一次 run 审计
     * （evaluation_run），任务创建/取消审计均在 run 之前写，保证 lastAuditLine 断言口径（M8）不被破坏。
     */
    private void writeAudit(Long tenantId, String action, String inputSummary,
                            String output, String operator) {
        writeAudit(tenantId, action, "SUCCESS", inputSummary, output, operator);
    }

    private void writeAudit(Long tenantId, String action, String status,
                            String inputSummary, String output, String operator) {
        try {
            AgentAudit a = new AgentAudit();
            a.setTenantId(tenantId);
            a.setAgentType("EVALUATION");
            a.setAction(action);
            a.setStatus(status);
            a.setInputSummary(inputSummary);
            a.setOutput(output);
            a.setSchemaValid(true);
            a.setStrategyVersion("rule");
            a.setModel("deterministic");
            a.setOperator(operator);
            a.setCreatedAt(Instant.now());
            auditMapper.insert(a);
        } catch (Exception e) {
            log.warn("评测审计写入失败（不影响业务）: {} {}: {}", action, status, e.getMessage());
        }
    }

    /** 审计 input_summary 顶层上下文（键值对展开为对象节点）。 */
    private String auditSummary(Object... kv) {
        ObjectNode n = json.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object k = kv[i];
            Object v = kv[i + 1];
            if (k instanceof String key) {
                if (v instanceof Number num) {
                    n.put(key, num.doubleValue());
                } else if (v instanceof String s) {
                    n.put(key, s);
                } else {
                    n.put(key, v == null ? "" : String.valueOf(v));
                }
            }
        }
        return writeOrNull(n);
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
    private List<Map<String, LlmJudgeScorer.JudgeDetail>> injectJudgeScores(ObjectNode input,
                                                                           List<String> metrics,
                                                                           int rounds, Long tenantId,
                                                                           String traceId,
                                                                           Map<Long, EvaluationCustomEvaluator> customsById) {
        List<Map<String, LlmJudgeScorer.JudgeDetail>> detailsByCase = new ArrayList<>();
        if (rounds <= 0) {
            return detailsByCase;
        }
        if (metrics == null || metrics.isEmpty()) {
            metrics = EvaluationModel.ALL_METRICS;
        }
        ArrayNode caseArr = (ArrayNode) input.path("cases");
        for (int i = 0; i < caseArr.size(); i++) {
            detailsByCase.add(new HashMap<>());
        }
        // 判分调用互相独立（LLM 生成慢、逐个串行会拖全量 run 数分钟），并行提交后按原顺序写回；
        // judgeDetailed 内部已捕获全部异常返回 null，join 不会抛；upsertCall 为单语句原子更新，并发记账安全。
        List<JudgeTask> tasks = new ArrayList<>();
        int caseIndex = 0;
        for (JsonNode cn : caseArr) {
            if (text(cn.path("actual_response")).isEmpty()) {
                caseIndex++;
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
                tasks.add(new JudgeTask(caseIndex, cn, metric, custom != null ? custom.getJudgePrompt()
                        : LlmJudgeScorer.defaultPrompt(metric),
                        text(cn.path("question")), text(cn.path("actual_response")),
                        text(cn.path("expected_output"))));
            }
            caseIndex++;
        }
        if (tasks.isEmpty()) {
            return detailsByCase;
        }
        List<CompletableFuture<LlmJudgeScorer.JudgeDetail>> futures = new ArrayList<>(tasks.size());
        for (JudgeTask t : tasks) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    judgeScorer.judgeDetailed(t.metric(), t.prompt(), t.question(), t.response(),
                            t.expected(), rounds, tenantId, traceId), JUDGE_POOL));
        }
        for (int i = 0; i < tasks.size(); i++) {
            LlmJudgeScorer.JudgeDetail detail = futures.get(i).join();
            if (detail == null) {
                continue;
            }
            JudgeTask t = tasks.get(i);
            ObjectNode judgeScores = t.caseNode().path("judge_scores").isObject()
                    ? (ObjectNode) t.caseNode().path("judge_scores") : null;
            if (judgeScores == null) {
                judgeScores = ((ObjectNode) t.caseNode()).putObject("judge_scores");
            }
            judgeScores.put(t.metric(), Math.round(detail.mean()));
            detailsByCase.get(t.caseIndex()).put(t.metric(), detail);
        }
        return detailsByCase;
    }

    /** 一条 LLM-Judge 判分任务（并行提交，写回按原顺序；caseIndex 对齐 cases[] 下标）。 */
    private record JudgeTask(int caseIndex, JsonNode caseNode, String metric, String prompt,
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
        writeAudit(tenantId, "EVALUATION_CUSTOM_CREATE", auditSummary(
                "customId", e.getId(), "name", e.getName(), "category", e.getCategory()),
                null, operator);
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
        writeAudit(tenantId, "EVALUATION_CUSTOM_UPDATE", auditSummary(
                "customId", e.getId(), "name", e.getName(), "category", e.getCategory()),
                null, operator);
        return toCustomView(e);
    }

    @Transactional
    public void deleteCustomEvaluator(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationCustomEvaluator e = requireCustom(id, tenantId);
        customEvaluatorMapper.deleteById(id);
        writeAudit(tenantId, "EVALUATION_CUSTOM_DELETE", auditSummary(
                "customId", e.getId(), "name", e.getName()), null, null);
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