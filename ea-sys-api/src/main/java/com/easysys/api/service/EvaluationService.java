package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.easysys.agent.AgentOutcome;
import com.easysys.agent.AgentPolicy;
import com.easysys.agent.AgentRunConfig;
import com.easysys.agent.EvaluationModel;
import com.easysys.api.config.AgentLlmProperties;
import com.easysys.api.dto.evaluation.CaseView;
import com.easysys.api.dto.evaluation.CustomEvaluatorView;
import com.easysys.api.dto.evaluation.DatasetView;
import com.easysys.api.dto.evaluation.DatasetVersionView;
import com.easysys.api.dto.evaluation.EvaluationRunRequest;
import com.easysys.api.dto.evaluation.ImportResultView;
import com.easysys.api.dto.evaluation.MetricCatalogView;
import com.easysys.api.dto.evaluation.DashboardView;
import com.easysys.api.dto.evaluation.HumanReviewView;
import com.easysys.api.dto.evaluation.PublishVersionRequest;
import com.easysys.api.dto.evaluation.ReportCompareView;
import com.easysys.api.dto.evaluation.ReportView;
import com.easysys.api.dto.evaluation.TaskDetailView;
import com.easysys.api.dto.evaluation.TaskView;
import com.easysys.api.dto.evaluation.TranscriptView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.EvaluationCase;
import com.easysys.api.entity.EvaluationCustomEvaluator;
import com.easysys.api.entity.EvaluationDataset;
import com.easysys.api.entity.EvaluationDatasetVersion;
import com.easysys.api.entity.EvaluationHumanReview;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.entity.EvaluationTask;
import com.easysys.api.entity.EvaluationTranscript;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.EvaluationCaseMapper;
import com.easysys.api.mapper.EvaluationCustomEvaluatorMapper;
import com.easysys.api.mapper.EvaluationDatasetMapper;
import com.easysys.api.mapper.EvaluationDatasetVersionMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
import com.easysys.api.mapper.EvaluationTaskMapper;
import com.easysys.api.mapper.EvaluationHumanReviewMapper;
import com.easysys.api.mapper.EvaluationTranscriptMapper;
import com.easysys.api.mapper.LlmUsageMapper;
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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
    private final EvaluationDatasetVersionMapper versionMapper;
    private final EvaluationTranscriptMapper transcriptMapper;
    private final EvaluationHumanReviewMapper humanReviewMapper;
    private final LlmUsageMapper llmUsageMapper;
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

    /** 成本估算单价（元/千 token）：deepseek 系公开价量级，注释为估算，非计费依据。 */
    private static final double INPUT_TOKEN_PRICE_YUAN_PER_1K = 0.002;
    private static final double OUTPUT_TOKEN_PRICE_YUAN_PER_1K = 0.006;

    /** 驾驶舱趋势默认条数与上限（A4）。 */
    private static final int DASHBOARD_LIMIT_DEFAULT = 12;
    private static final int DASHBOARD_LIMIT_MAX = 30;

    /** 分层三档（无 category 或未知值归入 basic）。 */
    private static final String LAYER_BASIC = "basic";
    private static final String LAYER_EDGE = "edge";
    private static final String LAYER_REAL = "real";

    public EvaluationService(EvaluationDatasetMapper datasetMapper, EvaluationCaseMapper caseMapper,
                             EvaluationReportMapper reportMapper, AgentAuditMapper auditMapper,
                             EvaluationCustomEvaluatorMapper customEvaluatorMapper,
                             EvaluationTaskMapper taskMapper,
                             EvaluationDatasetVersionMapper versionMapper,
                             EvaluationTranscriptMapper transcriptMapper,
                             EvaluationHumanReviewMapper humanReviewMapper,
                             LlmUsageMapper llmUsageMapper,
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
        this.versionMapper = versionMapper;
        this.transcriptMapper = transcriptMapper;
        this.humanReviewMapper = humanReviewMapper;
        this.llmUsageMapper = llmUsageMapper;
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
        List<EvaluationDataset> datasets = datasetMapper.selectList(new LambdaQueryWrapper<EvaluationDataset>()
                .eq(EvaluationDataset::getTenantId, tenantId)
                .orderByDesc(EvaluationDataset::getCreatedAt));
        if (datasets.isEmpty()) {
            return List.of();
        }
        List<Long> ids = datasets.stream().map(EvaluationDataset::getId).toList();
        // 批量取每数据集最新 PUBLISHED 版本 + 三档用例计数（避免 N+1）
        Map<Long, EvaluationDatasetVersion> latest = latestVersionByDataset(ids);
        Map<Long, long[]> categoryCounts = categoryCountsByDataset(ids);
        return datasets.stream()
                .map(d -> toDatasetView(d, latest.get(d.getId()), categoryCounts.get(d.getId())))
                .toList();
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

    /** 发布版本：当前工作区用例快照落版本（caseCount=快照数，created_by=操作人）。 */
    @Transactional
    public DatasetVersionView publishVersion(Long datasetId, PublishVersionRequest req, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationDataset d = requireDataset(datasetId, tenantId);
        List<EvaluationCase> cases = loadCases(datasetId);
        String snapshot = buildSnapshot(cases);
        EvaluationDatasetVersion latest = latestVersion(datasetId);
        if (latest != null && latest.getCases() != null
                && jsonEquals(latest.getCases(), snapshot)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版本内容无变更（与最新版本 v" + latest.getVersionNo()
                    + " 一致），无需发布");
        }
        EvaluationDatasetVersion v = new EvaluationDatasetVersion();
        v.setTenantId(tenantId);
        v.setDatasetId(datasetId);
        v.setVersionNo(versionMapper.nextVersionNo(datasetId));
        v.setStatus("PUBLISHED");
        v.setCases(snapshot);
        v.setEvaluators(null);
        v.setPublishedAt(Instant.now());
        v.setCreatedBy(operator);
        v.setCreatedAt(Instant.now());
        versionMapper.insert(v);
        writeAudit(tenantId, "EVALUATION_DATASET_VERSION_PUBLISH", auditSummary(
                "datasetId", datasetId, "datasetName", d.getName(), "versionId", v.getId(),
                "versionNo", v.getVersionNo(), "caseCount", cases.size()),
                null, operator);
        return toVersionView(v);
    }

    /** 版本列表（version_no 倒序）。 */
    public List<DatasetVersionView> listVersions(Long datasetId) {
        Long tenantId = TenantContext.require();
        requireDataset(datasetId, tenantId);
        return versionMapper.selectList(new LambdaQueryWrapper<EvaluationDatasetVersion>()
                        .eq(EvaluationDatasetVersion::getDatasetId, datasetId)
                        .orderByDesc(EvaluationDatasetVersion::getVersionNo))
                .stream().map(this::toVersionView).toList();
    }

    /** 版本用例快照回看（CaseView 同构，seq 升序；版本不存在 → 404）。 */
    public List<CaseView> listVersionCases(Long datasetId, Long versionId) {
        Long tenantId = TenantContext.require();
        requireDataset(datasetId, tenantId);
        EvaluationDatasetVersion v = requireVersion(versionId, tenantId, datasetId);
        return snapshotToCaseViews(v.getCases(), datasetId);
    }

    /** 删除版本：已被报告/任务引用 → 400（snapshot 永不可变，仅软删快照本身）。 */
    @Transactional
    public void deleteVersion(Long datasetId, Long versionId, String operator) {
        Long tenantId = TenantContext.require();
        requireDataset(datasetId, tenantId);
        EvaluationDatasetVersion v = requireVersion(versionId, tenantId, datasetId);
        long reportRefs = reportMapper.selectCount(new LambdaQueryWrapper<EvaluationReport>()
                .eq(EvaluationReport::getDatasetVersionId, versionId));
        long taskRefs = taskMapper.selectCount(new LambdaQueryWrapper<EvaluationTask>()
                .apply("params @> CAST({0} AS JSONB)",
                        "{\"usedVersion\":{\"versionId\":" + versionId + "}}"));
        if (reportRefs > 0 || taskRefs > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "版本已被报告/任务引用，禁止删除（版本为历史运行基线，请保留）");
        }
        versionMapper.deleteById(v.getId());
        writeAudit(tenantId, "EVALUATION_DATASET_VERSION_DELETE", auditSummary(
                "datasetId", datasetId, "versionId", versionId, "versionNo", v.getVersionNo(),
                "caseCount", snapshotCases(v.getCases()).size()),
                null, operator);
    }

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

    /** 删除数据集：级联软删用例、历史报告与版本快照（均含 deleted 列）。 */
    @Transactional
    public void deleteDataset(Long id) {
        Long tenantId = TenantContext.require();
        EvaluationDataset d = requireDataset(id, tenantId);
        datasetMapper.deleteById(d.getId());
        caseMapper.delete(new LambdaQueryWrapper<EvaluationCase>()
                .eq(EvaluationCase::getDatasetId, d.getId()));
        reportMapper.delete(new LambdaQueryWrapper<EvaluationReport>()
                .eq(EvaluationReport::getDatasetId, d.getId()));
        versionMapper.delete(new LambdaQueryWrapper<EvaluationDatasetVersion>()
                .eq(EvaluationDatasetVersion::getDatasetId, d.getId()));
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
        String addCategory = normalizeCategory(req.category());
        validateCategory(addCategory);
        c.setCategory(addCategory);
        c.setJudgeRule(jsonOrNull(req.judgeRule()));
        c.setDialogue(jsonOrNull(req.dialogue()));
        c.setExpectedOutput(jsonOrNull(req.expectedOutput()));
        c.setToolSchema(jsonOrNull(req.toolSchema()));
        c.setExpectedTool(jsonOrNull(req.expectedTool()));
        c.setExpectedSteps(req.expectedSteps() == null ? 1 : req.expectedSteps());
        c.setExpectedPolicy(jsonOrNull(req.expectedPolicy()));
        c.setExpectedKbHits(jsonOrNull(req.expectedKbHits()));
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
        if (req.expectedKbHits() != null) {
            c.setExpectedKbHits(jsonOrNull(req.expectedKbHits()));
        }
        if (req.providedResponse() != null) {
            c.setProvidedResponse(req.providedResponse());
        }
        if (req.category() != null && !req.category().isBlank()) {
            validateCategory(req.category());
            c.setCategory(req.category().trim());
        }
        if (req.judgeRule() != null) {
            c.setJudgeRule(jsonOrNull(req.judgeRule()));
        }
        if (req.dialogue() != null) {
            c.setDialogue(jsonOrNull(req.dialogue()));
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
        UsedVersion used = resolveVersion(d, req.datasetVersionId(), tenantId);
        List<EvaluationCase> cases = used.cases();
        if (cases.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集无可评测用例，请先添加用例");
        }

        // 判分轮次（1-5 多次取均值）+ 运行追踪 ID（驾驶舱 LLM 调用联动）
        int judgeRounds = clampRounds(req.judgeRounds());
        String traceId = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 租户自定义评测器（逻辑删除由 MyBatis-Plus 自动过滤）
        Map<Long, EvaluationCustomEvaluator> customsById = loadCustoms(tenantId);

        // 逐轮转录缓冲：execute 执行期间增量采集，报告落库后同事务批量写入
        List<EvaluationTranscript> transcripts = new ArrayList<>();
        ObjectNode input = buildInput(d, cases, judgeRounds, traceId, tenantId, operator,
                req.evaluators(), customsById, transcripts);

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
        appendLayeringFinding(outcome.output(), cases);
        persistAudit(tenantId, outcome, operator);
        ReportView report = persistReport(tenantId, d, cases, input, null, judgeRounds, traceId, operator,
                outcome.output(), used.versionId(), used.versionNo(),
                BigDecimal.valueOf(outcome.audit().confidence() == null ? 1.0 : outcome.audit().confidence()),
                outcome.audit().model() == null ? "deterministic" : outcome.audit().model());
        persistTranscripts(transcripts, report.id()); // 转录落库点：报告落库后同事务
        return report;
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
                                  Map<Long, EvaluationCustomEvaluator> customsById,
                                  List<EvaluationTranscript> transcripts) {
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
            n.put("category", normalizeCategory(c.getCategory()));
            setOrNull(n, "judge_rule", c.getJudgeRule());
            n.put("question", c.getQuestion());
            if (c.getSystemPrompt() != null) {
                n.put("system_prompt", c.getSystemPrompt());
            }
            setOrNull(n, "expected_output", c.getExpectedOutput());
            setOrNull(n, "expected_tool", c.getExpectedTool());
            setOrNull(n, "tool_schema", c.getToolSchema());
            n.put("expected_steps", c.getExpectedSteps() == null ? 1 : c.getExpectedSteps());
            setOrNull(n, "expected_policy", c.getExpectedPolicy());
            setOrNull(n, "expected_kb_hits", c.getExpectedKbHits());
            if (c.getProvidedResponse() != null) {
                n.put("provided_response", c.getProvidedResponse());
                n.put("actual_response", c.getProvidedResponse()); // openjudge：预置响应直接判分
            } else if ("execute".equals(d.getMode())) {
                // execute：真实运行被测智能体（多轮/转录/耗时采集），openjudge 不执行无 latency_ms
                executeSubject(n, subject, tenantId, operator, c, traceId, transcripts);
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

    /** 任务实际评测器集（与引擎缺省口径一致：未选中 = 全量 17 个内置）。 */
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

    /** 报告落库（run 与异步任务共用）：P3 起附带执行统计/环境/代码快照/分层/推荐/回归派生；
     * 异步任务走 perCaseScores（判分结果复用）；同步 run 传 null → 单用例确定性重算（仅供派生）。 */
    private ReportView persistReport(Long tenantId, EvaluationDataset d, List<EvaluationCase> cases,
                                     ObjectNode input, List<JsonNode> perCaseScores,
                                     int judgeRounds, String traceId, String operator, JsonNode output,
                                     Long datasetVersionId, Integer datasetVersionNo,
                                     BigDecimal confidence, String model) {
        Instant startedAt = Instant.now();
        EvaluationReport r = new EvaluationReport();
        r.setTenantId(tenantId);
        r.setDatasetId(d.getId());
        r.setName(d.getName());
        r.setTotalCases(cases.size());
        r.setTestedCases(output.path("tested_cases").asInt(cases.size()));
        r.setMetrics(writeOrNull(output.path("metrics")));
        r.setFindings(writeOrNull(output.path("findings")));
        ArrayNode caseArr = input == null || !input.path("cases").isArray()
                ? json.createArrayNode() : (ArrayNode) input.path("cases");
        List<JsonNode> caseScores = deriveCaseScores(input, caseArr, perCaseScores);
        r.setExecution(writeOrNull(buildExecutionStats(caseArr, tenantId, traceId, startedAt)));
        r.setEnvSnapshot(writeOrNull(buildEnvSnapshot()));
        r.setCodeSnapshot(writeOrNull(buildCodeSnapshot()));
        JsonNode layering = buildLayeringJson(caseArr, caseScores);
        r.setLayering(writeOrNull(layering));
        ObjectNode summary = output.path("summary").isObject()
                ? (ObjectNode) output.path("summary").deepCopy() : json.createObjectNode();
        EvaluationReport baseline = latestBaselineReport(tenantId, d.getId(), datasetVersionId);
        summary.set("recommendation", deriveRecommendation(output, baseline));
        summary.set("top_regressions", deriveTopRegressions(output, baseline, caseArr, caseScores));
        summary.set("layering", layering);
        r.setSummary(writeOrNull(summary));
        r.setConfidence(confidence);
        r.setModel(model);
        r.setMode(d.getMode());
        r.setJudgeRounds(judgeRounds);
        r.setTraceId(traceId);
        r.setDatasetVersionId(datasetVersionId);
        r.setDatasetVersionNo(datasetVersionNo);
        r.setCreatedBy(operator);
        r.setCreatedAt(Instant.now());
        reportMapper.insert(r);
        return toReportView(r);
    }

    /**
     * 转录落库：报告落库（同步 run 同事务）/任务 markCompleted 成功（异步）后批量插入，
     * report_id 回填后逐条 insert。转录为旁路数据——逐条 try/catch，失败仅降级该条并告警，
     * 绝不回滚评测主路径（与 llm_usage 采集同哲学）。
     */
    private void persistTranscripts(List<EvaluationTranscript> rows, Long reportId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (EvaluationTranscript t : rows) {
            try {
                t.setReportId(reportId);
                t.setCreatedAt(Instant.now());
                transcriptMapper.insert(t);
            } catch (Exception e) {
                log.warn("评测转录落库失败（reportId={}, seq={}, turn={}）: {}",
                        reportId, t.getCaseSeq(), t.getTurnNo(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
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

    // ---------- 逐轮转录（P2） ----------

    /**
     * 报告逐轮转录：指定用例（caseSeq 必填）全量轮次，turn_no 升序、同轮按插入序。
     * 报告不存在/跨租户 → 404（requireReport 守卫）。
     */
    public List<TranscriptView> listReportTranscript(Long reportId, Integer caseSeq) {
        Long tenantId = TenantContext.require();
        requireReport(reportId, tenantId);
        return transcriptMapper.selectList(new LambdaQueryWrapper<EvaluationTranscript>()
                        .eq(EvaluationTranscript::getReportId, reportId)
                        .eq(EvaluationTranscript::getCaseSeq, caseSeq)
                        .orderByAsc(EvaluationTranscript::getTurnNo)
                        .orderByAsc(EvaluationTranscript::getId))
                .stream().map(this::toTranscriptView).toList();
    }

    /**
     * 任务逐轮转录：经任务取报告（requireTask 租户守卫），未产出报告（RUNNING/取消且无报告）
     * 返回空列表；否则按报告查询（与清单一致：报告即转录归属）。
     */
    public List<TranscriptView> listTaskTranscript(Long taskId, Integer caseSeq) {
        Long tenantId = TenantContext.require();
        Long reportId = requireTask(taskId, tenantId).getReportId();
        if (reportId == null) {
            return List.of();
        }
        return listReportTranscript(reportId, caseSeq);
    }

    private TranscriptView toTranscriptView(EvaluationTranscript t) {
        return new TranscriptView(t.getTurnNo(), t.getRole(), t.getText(), t.getThinking(),
                parse(t.getToolUse()), parse(t.getToolResult()), t.getCreatedAt());
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

        // 绑定版本解析：显式 → 校验；缺省 → 最新 PUBLISHED（无 → 回退实时）。解析不写审计。
        UsedVersion used = resolveVersion(d, req.datasetVersionId(), tenantId);

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
        ObjectNode usedNode = params.putObject("usedVersion");
        usedNode.put("datasetId", d.getId());
        if (used.versionId() == null) {
            usedNode.putNull("versionId");
            usedNode.putNull("versionNo");
        } else {
            usedNode.put("versionId", used.versionId());
            usedNode.put("versionNo", used.versionNo());
        }

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
            if (d == null || "DISABLED".equals(d.getStatus())) {
                taskMapper.markFailed(taskId, tenantId, "数据集不存在或已停用，任务失败");
                return;
            }
            // 绑定版本：params.usedVersion.versionId 非 null → 快照用例；null → 实时工作区用例
            Long effectiveVersionId = null;
            Integer effectiveVersionNo = null;
            List<EvaluationCase> cases;
            JsonNode usedNode = params.path("usedVersion");
            JsonNode vIdNode = (usedNode.isMissingNode() || usedNode.isNull())
                    ? null : usedNode.path("versionId");
            if (vIdNode == null || vIdNode.isMissingNode() || vIdNode.isNull()) {
                cases = loadCases(t.getDatasetId());
            } else {
                effectiveVersionId = vIdNode.asLong();
                EvaluationDatasetVersion v = versionMapper.selectById(effectiveVersionId);
                if (v == null || !(d.getId().equals(v.getDatasetId()))) {
                    taskMapper.markFailed(taskId, tenantId, "数据集版本不存在或已被删除，任务失败");
                    return;
                }
                effectiveVersionNo = v.getVersionNo();
                cases = snapshotCases(v.getCases());
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
            // 逐轮转录缓冲：执行期间增量采集，markCompleted 成功（任务闭环）后落库
            List<EvaluationTranscript> transcripts = new ArrayList<>();
            ObjectNode input = buildInput(d, cases, judgeRounds, traceId, tenantId, operator,
                    selected.isEmpty() ? null : selected, customsById, transcripts);
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

            List<JsonNode> caseNodes = new ArrayList<>();
            caseArr.forEach(caseNodes::add);
            JsonNode output = aggregateOutput(perCaseOutputs, specs, caseArr.size(), d.getMode(), caseNodes);
            appendLayeringFinding(output, cases);
            writeAudit(tenantId, "evaluation_run", "SUCCESS",
                    writeOrNull(input), writeOrNull(output), operator);
            ReportView report = persistReport(tenantId, d, cases, input, perCaseOutputs, judgeRounds,
                    traceId, operator, output, effectiveVersionId, effectiveVersionNo,
                    BigDecimal.ONE, "deterministic");
            if (taskMapper.markCompleted(taskId, tenantId, report.id(), writeOrNull(samples)) == 0) {
                // 取消已抢先置 CANCELING：回滚刚落库的报告（转录随同放弃，未落库无孤儿），闭环为 CANCELED
                reportMapper.deleteById(report.id());
                taskMapper.markCanceled(taskId, tenantId, "任务已取消");
            } else {
                // 任务闭环成功 → 转录落库（绑定报告；report_id 非空外键，失败侧绝不落转录）
                persistTranscripts(transcripts, report.id());
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
     * score(0-1), passed(>=用例级 judge_rule.threshold 缺省 0.8), reason?, round_scores?}]}。
     * reason/round_scores 仅 LLM-Judge 且有真实判分明细时写入（规则类恒缺），LLM 未启用时为空属合法。
     */
    private ObjectNode buildSample(JsonNode cn, JsonNode out,
                                   Map<String, LlmJudgeScorer.JudgeDetail> details, String mode) {
        ObjectNode sample = json.createObjectNode();
        sample.put("seq", cn.path("seq").asInt());
        sample.put("question", truncate(cn.path("question").asText(""), 200));
        sample.put("actual_response", truncate(cn.path("actual_response").asText(""), 500));
        // 整例执行耗时（execute 专属，openjudge 无此键）：仅存在时写入，不影响 metrics 判分
        if (cn.path("latency_ms").isNumber()) {
            sample.put("latency_ms", cn.path("latency_ms").asLong());
        }
        // 分层（P3：compare layer 过滤/layering 统计数据源）与整例步数（execute 专属）
        sample.put("category", cn.path("category").asText("basic"));
        if (cn.path("actual_steps").isInt()) {
            sample.put("actual_steps", cn.path("actual_steps").asInt());
        }
        sample.put("mode", mode);
        ArrayNode metrics = sample.putArray("metrics");
        for (JsonNode m : out.path("metrics")) {
            String metric = m.path("metric").asText();
            double score = m.path("avg_score").asDouble();
            ObjectNode row = metrics.addObject();
            row.put("metric", metric);
            row.put("category", m.path("category").asText(""));
            row.put("score", round4(score));
            row.put("passed", score >= caseThreshold(cn, metric));
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
     * 与引擎批量 build() 口径完全一致（纯函数、同 selected 顺序）。
     * passed 阈值取用例级 judge_rule 覆盖（缺省 0.8）；分级发现仍 0.6/0.8 全局口径。
     */
    private ObjectNode aggregateOutput(List<JsonNode> perCaseOutputs, List<JsonNode> specs,
                                       int totalCases, String inMode, List<JsonNode> caseNodes) {
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
            int passed = 0;
            int idx = 0;
            for (JsonNode po : perCaseOutputs) {
                double thr = caseNodes != null && idx < caseNodes.size()
                        ? caseThreshold(caseNodes.get(idx), metric) : 0.8;
                for (JsonNode m : po.path("metrics")) {
                    if (metric.equals(m.path("metric").asText()) && m.path("avg_score").isNumber()
                            && m.path("avg_score").asDouble() >= thr) {
                        passed++;
                    }
                }
                idx++;
            }
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
    /** 报告对比：layer 可选按分层过滤 metric 均值（仅两报告均有逐样本数据时逐样本重算，
     * 否则回退全局均值 + layer 回显）；topDegradedSamples 为逐样本 |delta| 降序前 10。
     */
    public ReportCompareView compareReports(Long currentId, Long baselineId, String layer) {
        Long tenantId = TenantContext.require();
        if (baselineId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "compare 需指定 baseline 报告 ID");
        }
        String layerNorm = validateLayer(layer);
        EvaluationReport current = requireReport(currentId, tenantId);
        EvaluationReport baseline = requireReport(baselineId, tenantId);
        Map<Long, JsonNode> currentSamples = reportSamplesBySeq(currentId);
        Map<Long, JsonNode> baselineSamples = reportSamplesBySeq(baselineId);
        Map<String, CompareSlot> byMetric = new LinkedHashMap<>();
        if (layerNorm != null && !currentSamples.isEmpty() && !baselineSamples.isEmpty()) {
            fillSlotsFiltered(byMetric, currentSamples, baselineSamples, layerNorm);
        } else {
            fillSlots(byMetric, current, true);
            fillSlots(byMetric, baseline, false);
        }
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
                rows, layerNorm, topDegradedSamples(currentSamples, baselineSamples, layerNorm));
    }

    /** layer 参数归一（null/空 → null；非 basic/edge/real → 400）。 */
    private static String validateLayer(String layer) {
        if (layer == null || layer.isBlank()) {
            return null;
        }
        String l = layer.trim();
        if (!"basic".equals(l) && !"edge".equals(l) && !"real".equals(l)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 layer（basic/edge/real）: " + layer);
        }
        return l;
    }

    /** 报告关联任务逐样本（seq → sample；无任务/无样本 → 空 Map）。 */
    private Map<Long, JsonNode> reportSamplesBySeq(Long reportId) {
        Map<Long, JsonNode> bySeq = new LinkedHashMap<>();
        EvaluationTask task = taskMapper.selectList(new LambdaQueryWrapper<EvaluationTask>()
                        .eq(EvaluationTask::getReportId, reportId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (task == null) {
            return bySeq;
        }
        JsonNode samples = parse(task.getSampleResults());
        if (samples != null && samples.isArray()) {
            samples.forEach(s -> bySeq.put(s.path("seq").asLong(), s));
        }
        return bySeq;
    }

    /** 分层过滤版逐指标均值：双报告同 layer 的逐样本按 seq 交集对齐重算（层内无分的指标行剔除）。 */
    private void fillSlotsFiltered(Map<String, CompareSlot> byMetric,
                                   Map<Long, JsonNode> currentSamples,
                                   Map<Long, JsonNode> baselineSamples, String layer) {
        Set<Long> seqs = new TreeSet<>(currentSamples.keySet());
        seqs.retainAll(baselineSamples.keySet());
        Map<String, List<Double>> cur = new LinkedHashMap<>();
        Map<String, List<Double>> base = new LinkedHashMap<>();
        Map<String, String> cats = new LinkedHashMap<>();
        for (Long seq : seqs) {
            JsonNode cs = currentSamples.get(seq);
            JsonNode bs = baselineSamples.get(seq);
            if (!layer.equals(cs.path("category").asText("basic"))
                    || !layer.equals(bs.path("category").asText("basic"))) {
                continue; // 双报告同层才纳入
            }
            collectSampleScores(cur, cats, cs);
            collectSampleScores(base, cats, bs);
        }
        for (Map.Entry<String, List<Double>> e : cur.entrySet()) {
            List<Double> b = base.get(e.getKey());
            if (b == null || b.isEmpty()) {
                continue; // 该层无此指标的基线样本 → 无对齐可比，剔除
            }
            CompareSlot slot = new CompareSlot(e.getKey(), cats.get(e.getKey()));
            slot.current = round4(meanOf(e.getValue()));
            slot.baseline = round4(meanOf(b));
            byMetric.put(e.getKey(), slot);
        }
    }

    private void collectSampleScores(Map<String, List<Double>> acc,
                                     Map<String, String> cats, JsonNode sample) {
        for (JsonNode m : sample.path("metrics")) {
            String metric = m.path("metric").asText();
            if (metric.isEmpty() || !m.path("score").isNumber()) {
                continue;
            }
            acc.computeIfAbsent(metric, k -> new ArrayList<>()).add(m.path("score").asDouble());
            cats.putIfAbsent(metric, m.path("category").asText(""));
        }
    }

    /** 逐样本退化榜：同 seq 当前/基线整例均值差 |delta| 降序前 10（任一侧缺逐样本 → 空）。 */
    private List<ReportCompareView.TopDegradedSample> topDegradedSamples(
            Map<Long, JsonNode> currentSamples, Map<Long, JsonNode> baselineSamples, String layer) {
        List<ReportCompareView.TopDegradedSample> out = new ArrayList<>();
        if (currentSamples.isEmpty() || baselineSamples.isEmpty()) {
            return out;
        }
        List<double[]> rows = new ArrayList<>(); // {|d|, seq, auto, baseline, delta}
        for (Map.Entry<Long, JsonNode> e : currentSamples.entrySet()) {
            JsonNode bs = baselineSamples.get(e.getKey());
            if (bs == null) {
                continue;
            }
            if (layer != null
                    && !layer.equals(e.getValue().path("category").asText("basic"))
                    && !layer.equals(bs.path("category").asText("basic"))) {
                continue;
            }
            double auto = perSampleMean(e.getValue());
            double base = perSampleMean(bs);
            if (auto < 0 || base < 0) {
                continue; // 任一侧该样本无适用指标 → 不对齐
            }
            double delta = auto - base;
            rows.add(new double[]{Math.abs(delta), e.getKey(), auto, base, delta});
        }
        rows.sort((a, b) -> Double.compare(b[0], a[0]));
        for (int k = 0; k < Math.min(10, rows.size()); k++) {
            double[] d = rows.get(k);
            out.add(new ReportCompareView.TopDegradedSample(
                    (long) d[1], d[2], d[3], round4(d[4])));
        }
        return out;
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

    // ---------- 报告驱动派生（P3）：execution / 快照 / layering / recommendation / top_regressions ----------

    /** 核心指标（A2 上线建议门禁集）。 */
    private static final List<String> CORE_METRICS = List.of(
            "task_success", "tool_call_accuracy", "decision_accuracy", "policy_compliance");

    /** Map 取数值（null → 0）。 */
    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** 逐用例判分结果：perCaseScores 非 null 直接复用；null（同步 run）按单用例确定性重算（仅供派生）。 */
    private List<JsonNode> deriveCaseScores(ObjectNode input, ArrayNode caseArr, List<JsonNode> perCaseScores) {
        if (perCaseScores != null) {
            return perCaseScores;
        }
        List<JsonNode> scores = new ArrayList<>();
        for (JsonNode cn : caseArr) {
            scores.add(new EvaluationModel().plan(singleCaseInput(input, cn)));
        }
        return scores;
    }

    /** 执行统计（A1）：耗时在 persistReport 内测量；延迟/步数取 input case 节点（execute 专属，
     * openjudge 缺 → null）；llm_usage 按 traceId 聚合（subject 会话 session_id LIKE traceId%，
     * judge 会话 agent_type='evaluation' 且 session_id=traceId）；成本 = in×0.002 + out×0.006 元/千 token。
     * LLM 未启用（无 usage 行）→ tokens 与成本 JSON null（前端显「—」）。 */
    private ObjectNode buildExecutionStats(ArrayNode caseArr, Long tenantId, String traceId, Instant startedAt) {
        ObjectNode e = json.createObjectNode();
        List<Long> latencies = new ArrayList<>();
        List<Long> steps = new ArrayList<>();
        for (JsonNode cn : caseArr) {
            if (cn.path("latency_ms").isNumber()) {
                latencies.add(cn.path("latency_ms").asLong());
            }
            if (cn.path("actual_steps").isInt()) {
                steps.add(cn.path("actual_steps").asLong());
            }
        }
        e.put("total_duration_ms", Duration.between(startedAt, Instant.now()).toMillis());
        if (latencies.isEmpty()) {
            e.putNull("avg_latency_ms");
            e.putNull("p50_latency_ms");
            e.putNull("p95_latency_ms");
        } else {
            e.put("avg_latency_ms", Math.round(meanOf(latencies.stream().map(Double::valueOf).toList())));
            e.put("p50_latency_ms", percentile(latencies, 0.50));
            e.put("p95_latency_ms", percentile(latencies, 0.95));
        }
        if (steps.isEmpty()) {
            e.putNull("avg_steps");
            e.putNull("total_steps");
        } else {
            e.put("avg_steps", round2(steps.stream().mapToLong(Long::longValue).average().orElse(0)));
            e.put("total_steps", steps.stream().mapToLong(Long::longValue).sum());
        }
        Map<String, Object> usage = (traceId == null || traceId.isBlank())
                ? Map.of() : llmUsageMapper.selectRunUsage(tenantId, traceId, traceId + "%");
        if (usage.isEmpty()) {
            e.putNull("llm_calls");
            e.putNull("input_tokens");
            e.putNull("output_tokens");
            e.putNull("judge_calls");
            e.putNull("judge_input_tokens");
            e.putNull("judge_output_tokens");
            e.putNull("estimated_cost_cny");
        } else {
            long inputTokens = num(usage.get("input_tokens"));
            long outputTokens = num(usage.get("output_tokens"));
            e.put("llm_calls", num(usage.get("llm_calls")));
            e.put("input_tokens", inputTokens);
            e.put("output_tokens", outputTokens);
            e.put("judge_calls", num(usage.get("judge_calls")));
            e.put("judge_input_tokens", num(usage.get("judge_input_tokens")));
            e.put("judge_output_tokens", num(usage.get("judge_output_tokens")));
            e.put("estimated_cost_cny", round4((inputTokens / 1000.0) * INPUT_TOKEN_PRICE_YUAN_PER_1K
                    + (outputTokens / 1000.0) * OUTPUT_TOKEN_PRICE_YUAN_PER_1K));
        }
        return e;
    }

    /** 百分位（p ∈ [0,1]）：升序后取 round(p×(n-1)) 下标元素；空 → null。 */
    private static Long percentile(List<Long> xs, double p) {
        if (xs == null || xs.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(xs);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(idx);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 环境快照（T2）：应用/运行时/LLM 配置 + 被测智能体 delegate 名（无则回退 agentType）。 */
    private ObjectNode buildEnvSnapshot() {
        ObjectNode e = json.createObjectNode();
        String apiVersion = System.getProperty("api.version");
        e.put("app_version", apiVersion == null || apiVersion.isBlank() ? "0.1.0-SNAPSHOT" : apiVersion);
        e.put("java_version", System.getProperty("java.version", "unknown"));
        e.put("llm_enabled", llm.isEnabled());
        String modelId = llm.getModelId();
        if (modelId == null) {
            e.putNull("llm_model_id");
        } else {
            e.put("llm_model_id", modelId);
        }
        ObjectNode models = e.putObject("agent_models");
        models.put("assistant", delegateName(assistantAgent, "assistant"));
        models.put("workflow-dialogue", delegateName(workflowDialogueAgent, "workflow-dialogue"));
        return e;
    }

    /** agent delegate 名（ReActAgent 经 Agent 接口暴露 getName；null → agentType 兜底）。 */
    private static String delegateName(HarnessAgent agent, String fallback) {
        try {
            ReActAgent delegate = agent.getDelegate();
            String name = delegate == null ? null : delegate.getName();
            return name == null || name.isBlank() ? fallback : name;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 代码快照（T2）：user.dir 逐级向上找 .git/HEAD → refs/heads/<branch> 取 commit 前 7；
     * 失败 → git_commit/git_branch JSON null，build_time 恒有。 */
    private ObjectNode buildCodeSnapshot() {
        ObjectNode e = json.createObjectNode();
        File gitDir = findGitDir(new File(System.getProperty("user.dir", ".")));
        String commit = null;
        String branch = null;
        if (gitDir != null) {
            try {
                Path head = Path.of(gitDir.getAbsolutePath(), "HEAD");
                if (Files.isRegularFile(head)) {
                    String content = Files.readString(head).trim();
                    if (content.startsWith("ref: ")) {
                        String ref = content.substring("ref: ".length());
                        branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : null;
                        Path refFile = Path.of(gitDir.getAbsolutePath(), ref);
                        if (Files.isRegularFile(refFile)) {
                            String c = Files.readString(refFile).trim();
                            commit = c.length() >= 7 ? c.substring(0, 7) : (c.isEmpty() ? null : c);
                        }
                    } else if (content.length() >= 7) {
                        commit = content.substring(0, 7); // detached HEAD
                    }
                }
            } catch (Exception ex) {
                log.debug("评测 code_snapshot 读取 git 元数据失败: {}", ex.getMessage());
            }
        }
        if (commit == null) {
            e.putNull("git_commit");
        } else {
            e.put("git_commit", commit);
        }
        if (branch == null) {
            e.putNull("git_branch");
        } else {
            e.put("git_branch", branch);
        }
        e.put("build_time", Instant.now().toString());
        return e;
    }

    /** 逐级向上找 .git 目录（.git 为文件（worktree）时不解析 refs，返回 null）。 */
    private static File findGitDir(File start) {
        File cur = start;
        while (cur != null) {
            File git = new File(cur, ".git");
            if (git.isDirectory()) {
                return git;
            }
            cur = cur.getParentFile();
        }
        return null;
    }

    /** layering 分层聚合（A3）：三档 {count, tested, pass_rate}；category 缺省 basic。 */
    private JsonNode buildLayeringJson(ArrayNode caseArr, List<JsonNode> caseScores) {
        ObjectNode out = json.createObjectNode();
        LayerAgg basic = new LayerAgg();
        LayerAgg edge = new LayerAgg();
        LayerAgg real = new LayerAgg();
        for (int i = 0; i < caseArr.size(); i++) {
            String cat = normalizeLayer(caseArr.get(i).path("category").asText("basic"));
            LayerAgg agg = "edge".equals(cat) ? edge : ("real".equals(cat) ? real : basic);
            agg.count++;
            JsonNode outNode = caseScores != null && i < caseScores.size() ? caseScores.get(i) : null;
            double mean = perCaseMean(outNode);
            if (mean < 0) {
                continue; // 无实际判分结果（缺数据）不参与适用数
            }
            agg.tested++;
            if (mean >= 0.8) {
                agg.passed++;
            }
        }
        out.set("basic", layerNode(basic));
        out.set("edge", layerNode(edge));
        out.set("real", layerNode(real));
        return out;
    }

    private ObjectNode layerNode(LayerAgg agg) {
        ObjectNode n = json.createObjectNode();
        n.put("count", agg.count);
        n.put("tested", agg.tested);
        n.put("pass_rate", agg.tested == 0 ? 0.0 : round4(agg.passed / (double) agg.tested));
        return n;
    }

    private static String normalizeLayer(String cat) {
        if ("edge".equals(cat)) {
            return "edge";
        }
        if ("real".equals(cat)) {
            return "real";
        }
        return "basic";
    }

    /** 分层聚合累加器。 */
    private static final class LayerAgg {
        int count;
        int tested;
        int passed;
    }

    /** 基线报告：同数据集最新（id 降序）——优先同 datasetVersionId；无 → 同数据集最新。 */
    private EvaluationReport latestBaselineReport(Long tenantId, Long datasetId, Long datasetVersionId) {
        if (datasetVersionId != null) {
            EvaluationReport r = reportMapper.selectList(new LambdaQueryWrapper<EvaluationReport>()
                            .eq(EvaluationReport::getTenantId, tenantId)
                            .eq(EvaluationReport::getDatasetId, datasetId)
                            .eq(EvaluationReport::getDatasetVersionId, datasetVersionId)
                            .orderByDesc(EvaluationReport::getId)
                            .last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            if (r != null) {
                return r;
            }
        }
        return reportMapper.selectList(new LambdaQueryWrapper<EvaluationReport>()
                        .eq(EvaluationReport::getTenantId, tenantId)
                        .eq(EvaluationReport::getDatasetId, datasetId)
                        .orderByDesc(EvaluationReport::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 指标集合 → metric→avg_score（无评分指标跳过）。 */
    private static Map<String, Double> metricAverages(JsonNode metrics) {
        Map<String, Double> map = new LinkedHashMap<>();
        if (metrics != null && metrics.isArray()) {
            for (JsonNode m : metrics) {
                if (m.path("avg_score").isNumber()) {
                    map.put(m.path("metric").asText(), m.path("avg_score").asDouble());
                }
            }
        }
        return map;
    }

    /**
     * 上线建议（A2）：任一核心 <0.6 或相对基线回归 >0.1 → NO_GO；任一 <0.8 或回归 >0.05 → WATCH；
     * 全部 ≥0.8 且无显著回归 → GO。reason = 指标名+数值（基线存在带 Δ）。无基线只按绝对值判定。
     */
    private ObjectNode deriveRecommendation(JsonNode output, EvaluationReport baseline) {
        Map<String, Double> cur = metricAverages(output.path("metrics"));
        Map<String, Double> prev = baseline == null ? Map.of() : metricAverages(parse(baseline.getMetrics()));
        String verdict = "GO";
        List<String> reasons = new ArrayList<>();
        for (String m : CORE_METRICS) {
            Double c = cur.get(m);
            if (c == null) {
                continue;
            }
            Double b = prev.get(m);
            boolean noBaseline = b == null;
            double delta = noBaseline ? 0 : c - b;
            reasons.add(m + "=" + formatScore(c) + (noBaseline ? "" : "(Δ" + formatSigned(delta) + ")"));
            if (c < 0.6 || (!noBaseline && delta < -0.1)) {
                verdict = "NO_GO";
            } else if (!"NO_GO".equals(verdict)
                    && (c < 0.8 || (!noBaseline && delta < -0.05))) {
                verdict = "WATCH";
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("无核心指标适用");
        }
        ObjectNode rec = json.createObjectNode();
        rec.put("verdict", verdict);
        rec.put("reason", String.join("; ", reasons));
        return rec;
    }

    private static String formatScore(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String formatSigned(double v) {
        if (v > 0) {
            return "+" + String.format(Locale.ROOT, "%.2f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Top 回归（A3）：无基线 → {metrics:[], samples:[]}；metrics = 与基线共有指标 delta 升序
     * （最差回归在前）前 5；samples = 逐样本（当前 per-case 均值 vs 基线任务样本均值，|delta|
     * 降序前 5，{seq, auto, baseline, delta}）。
     */
    private ObjectNode deriveTopRegressions(JsonNode output, EvaluationReport baseline,
                                            ArrayNode caseArr, List<JsonNode> caseScores) {
        ObjectNode reg = json.createObjectNode();
        ArrayNode metrics = reg.putArray("metrics");
        ArrayNode samples = reg.putArray("samples");
        if (baseline == null) {
            return reg;
        }
        Map<String, Double> cur = metricAverages(output.path("metrics"));
        Map<String, Double> prev = metricAverages(parse(baseline.getMetrics()));
        List<double[]> deltas = new ArrayList<>(); // {delta, current, baseline}
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Double> e : cur.entrySet()) {
            Double b = prev.get(e.getKey());
            if (b == null) {
                continue;
            }
            deltas.add(new double[]{e.getValue() - b, e.getValue(), b});
            names.add(e.getKey());
        }
        int[] order = sortedIndices(deltas, true);
        for (int k = 0; k < Math.min(5, order.length); k++) {
            double[] d = deltas.get(order[k]);
            ObjectNode row = metrics.addObject();
            row.put("metric", names.get(order[k]));
            row.put("current", round4(d[1]));
            row.put("baseline", round4(d[2]));
            row.put("delta", round4(d[0]));
        }
        Map<Long, Double> baseBySeq = baselineSampleMeans(baseline.getId());
        if (!baseBySeq.isEmpty()) {
            List<double[]> sampleRows = new ArrayList<>(); // {|d|, seq, auto, baseline, delta}
            for (int i = 0; i < caseArr.size(); i++) {
                double auto = perCaseMean(caseScores != null && i < caseScores.size() ? caseScores.get(i) : null);
                if (auto < 0) {
                    continue;
                }
                long seq = caseArr.get(i).path("seq").asLong();
                Double base = baseBySeq.get(seq);
                if (base == null) {
                    continue;
                }
                double delta = auto - base;
                sampleRows.add(new double[]{Math.abs(delta), seq, auto, base, delta});
            }
            sampleRows.sort((a, b) -> Double.compare(b[0], a[0]));
            for (int k = 0; k < Math.min(5, sampleRows.size()); k++) {
                double[] d = sampleRows.get(k);
                ObjectNode row = samples.addObject();
                row.put("seq", (long) d[1]);
                row.put("auto", round4(d[2]));
                row.put("baseline", round4(d[3]));
                row.put("delta", round4(d[4]));
            }
        }
        return reg;
    }

    /** 索引排序（rows[i][0] 为排序键；ascending=true 升序）。 */
    private static int[] sortedIndices(List<double[]> rows, boolean ascending) {
        Integer[] boxed = new Integer[rows.size()];
        for (int i = 0; i < boxed.length; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> ascending
                ? Double.compare(rows.get(a)[0], rows.get(b)[0])
                : Double.compare(rows.get(b)[0], rows.get(a)[0]));
        int[] idx = new int[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            idx[i] = boxed[i];
        }
        return idx;
    }

    /** per-case 输出整例均值（适用指标 avg_score 均值；无适用指标 → -1）。 */
    private static double perCaseMean(JsonNode out) {
        if (out == null) {
            return -1;
        }
        List<Double> xs = new ArrayList<>();
        for (JsonNode m : out.path("metrics")) {
            if (m.path("avg_score").isNumber()) {
                xs.add(m.path("avg_score").asDouble());
            }
        }
        return xs.isEmpty() ? -1 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(-1);
    }

    /** 逐样本整例均值（适用指标 score 均值；无适用指标 → -1）。 */
    private static double perSampleMean(JsonNode sample) {
        List<Double> xs = new ArrayList<>();
        for (JsonNode m : sample.path("metrics")) {
            if (m.path("score").isNumber()) {
                xs.add(m.path("score").asDouble());
            }
        }
        return xs.isEmpty() ? -1 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(-1);
    }

    /** 基线报告逐样本均值（seq → 整例均值；无任务/无样本 → 空 Map）。 */
    private Map<Long, Double> baselineSampleMeans(Long reportId) {
        Map<Long, Double> bySeq = new LinkedHashMap<>();
        for (Map.Entry<Long, JsonNode> e : reportSamplesBySeq(reportId).entrySet()) {
            double mean = perSampleMean(e.getValue());
            if (mean >= 0) {
                bySeq.put(e.getKey(), mean);
            }
        }
        return bySeq;
    }

    /** 用例 judge_rule 命中 metric 的规则节点（对象自身或数组内命中项）；无 → null。 */
    private static JsonNode judgeRuleFor(JsonNode cn, String metric) {
        JsonNode jr = cn.path("judge_rule");
        if (jr.isMissingNode() || jr.isNull()) {
            return null;
        }
        if (jr.isObject()) {
            return metric.equals(jr.path("metric").asText("")) ? jr : null;
        }
        if (jr.isArray()) {
            for (JsonNode e : jr) {
                if (metric.equals(e.path("metric").asText(""))) {
                    return e;
                }
            }
        }
        return null;
    }

    /** 用例级通过阈值：judge_rule 命中 metric 的 threshold 覆盖，缺省 0.8。 */
    private static double caseThreshold(JsonNode cn, String metric) {
        JsonNode rule = judgeRuleFor(cn, metric);
        return rule != null && rule.path("threshold").isNumber()
                ? rule.path("threshold").asDouble() : 0.8;
    }

    // ---------- 人工复评（P3）：submit / list / delete / calibration ----------

    /**
     * 人工复评提交/改判（S3）：同 (reportId, caseSeq, metric) 软删旧行 + 插新（upsert，唯一键含
     * deleted 保证同参可重复提交）。metric 缺省 '*'（纯人工整分）；score∈[0,1]；verdict 缺省由
     * score 派生（≥0.8 PASS / ≥0.6 WARN / FAIL）。
     * 审计 EVALUATION_REVIEW_SUBMIT（evaluation_run 绝不触碰，M8 审计计数契约不受影响）。
     */
    @Transactional
    public HumanReviewView submitReview(Long reportId, HumanReviewView.SaveRequest req, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationReport r = requireReport(reportId, tenantId);
        if (req == null || req.caseSeq() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "caseSeq 必填");
        }
        int maxSeq = Math.max(r.getTotalCases() == null ? 0 : r.getTotalCases(),
                r.getTestedCases() == null ? 0 : r.getTestedCases());
        if (req.caseSeq() < 1 || req.caseSeq() > maxSeq) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "caseSeq 越界（1-" + maxSeq + "）: " + req.caseSeq());
        }
        BigDecimal score = req.score();
        if (score != null && (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "score 必须 ∈ [0,1]");
        }
        String metric = req.metric() == null || req.metric().isBlank() ? "*" : req.metric().trim();
        String verdict = req.verdict() == null || req.verdict().isBlank() ? verdictOf(score) : req.verdict().trim();
        if (verdict != null && !List.of("PASS", "WARN", "FAIL").contains(verdict)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "verdict 非法（PASS/WARN/FAIL）: " + verdict);
        }
        humanReviewMapper.delete(new LambdaQueryWrapper<EvaluationHumanReview>()
                .eq(EvaluationHumanReview::getTenantId, tenantId)
                .eq(EvaluationHumanReview::getReportId, reportId)
                .eq(EvaluationHumanReview::getCaseSeq, req.caseSeq())
                .eq(EvaluationHumanReview::getMetric, metric));
        EvaluationHumanReview h = new EvaluationHumanReview();
        h.setTenantId(tenantId);
        h.setReportId(reportId);
        h.setCaseSeq(req.caseSeq());
        h.setMetric(metric);
        h.setScore(score);
        h.setVerdict(verdict);
        h.setNote(req.note());
        h.setReviewer(operator);
        h.setCreatedAt(Instant.now());
        h.setUpdatedAt(Instant.now());
        humanReviewMapper.insert(h);
        writeAudit(tenantId, "EVALUATION_REVIEW_SUBMIT", auditSummary(
                "reportId", reportId, "caseSeq", req.caseSeq(), "metric", metric,
                "score", score == null ? 0 : score.doubleValue(),
                "verdict", verdict == null ? "" : verdict),
                null, operator);
        return toReviewView(h, null);
    }

    /** 缺省 verdict：score ≥0.8 PASS / ≥0.6 WARN / 其余 FAIL；score 空 → null。 */
    private static String verdictOf(BigDecimal score) {
        if (score == null) {
            return null;
        }
        double s = score.doubleValue();
        return s >= 0.8 ? "PASS" : (s >= 0.6 ? "WARN" : "FAIL");
    }

    /**
     * 复评列表（caseSeq/创建升序）；autoScore=true 时逐条派生自动分（关联任务逐样本
     * metric→score，'*' 取整例均值；同步 run 报告无逐样本 → auto=null）。
     */
    public List<HumanReviewView> listReviews(Long reportId, boolean autoScore) {
        Long tenantId = TenantContext.require();
        requireReport(reportId, tenantId);
        Map<Long, Map<String, Double>> auto = autoScore ? sampleMetricScores(reportId) : Map.of();
        return humanReviewMapper.selectList(new LambdaQueryWrapper<EvaluationHumanReview>()
                        .eq(EvaluationHumanReview::getTenantId, tenantId)
                        .eq(EvaluationHumanReview::getReportId, reportId)
                        .orderByAsc(EvaluationHumanReview::getCaseSeq)
                        .orderByAsc(EvaluationHumanReview::getId))
                .stream()
                .map(h -> toReviewView(h, autoScore ? sampleScore(auto, h.getCaseSeq(), h.getMetric()) : null))
                .toList();
    }

    /** 报告逐样本 metric→score（seq → {metric, score}；无任务/无样本 → 空 Map）。 */
    private Map<Long, Map<String, Double>> sampleMetricScores(Long reportId) {
        Map<Long, Map<String, Double>> bySeq = new LinkedHashMap<>();
        for (Map.Entry<Long, JsonNode> e : reportSamplesBySeq(reportId).entrySet()) {
            Map<String, Double> byMetric = new LinkedHashMap<>();
            for (JsonNode m : e.getValue().path("metrics")) {
                if (m.path("score").isNumber()) {
                    byMetric.put(m.path("metric").asText(), m.path("score").asDouble());
                }
            }
            bySeq.put(e.getKey(), byMetric);
        }
        return bySeq;
    }

    /** 逐样本自动分：'*' → 适用指标整例均值；无样本/指标不适用 → null。 */
    private static Double sampleScore(Map<Long, Map<String, Double>> bySeq, Integer caseSeq, String metric) {
        if (caseSeq == null) {
            return null;
        }
        Map<String, Double> byMetric = bySeq.get(caseSeq.longValue());
        if (byMetric == null || byMetric.isEmpty()) {
            return null;
        }
        if ("*".equals(metric)) {
            return round4(meanOf(new ArrayList<>(byMetric.values())));
        }
        Double s = byMetric.get(metric);
        return s == null ? null : round4(s);
    }

    private HumanReviewView toReviewView(EvaluationHumanReview h, Double auto) {
        return new HumanReviewView(h.getId(), h.getReportId(), h.getCaseSeq(), h.getMetric(),
                h.getScore(), h.getVerdict(), h.getNote(), h.getReviewer(), auto, h.getCreatedAt());
    }

    /** 删除单条复评（租户守卫）；审计 EVALUATION_REVIEW_DELETE。 */
    @Transactional
    public void deleteReview(Long id, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationHumanReview h = humanReviewMapper.selectById(id);
        if (h == null || !tenantId.equals(h.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "人工复评不存在: " + id);
        }
        humanReviewMapper.deleteById(id);
        writeAudit(tenantId, "EVALUATION_REVIEW_DELETE", auditSummary(
                "reviewId", id, "reportId", h.getReportId(),
                "caseSeq", h.getCaseSeq(), "metric", h.getMetric()),
                null, operator);
    }

    /**
     * 校准对比（S4）：overall（'*' 人工整分黄金标准 n/meanHuman/passRate）+ 逐指标 auto-vs-human
     * 对齐（meanAuto/meanHuman/meanAbsDiff/agreementRate(|delta|<0.5 视为一致)/topDeltas |delta| 前 10）。
     * 仅审计口径——不写 evaluation_run，不破坏 M8 审计计数契约。
     */
    public JsonNode calibration(Long reportId) {
        Long tenantId = TenantContext.require();
        requireReport(reportId, tenantId);
        List<EvaluationHumanReview> rows = humanReviewMapper.selectList(
                new LambdaQueryWrapper<EvaluationHumanReview>()
                        .eq(EvaluationHumanReview::getTenantId, tenantId)
                        .eq(EvaluationHumanReview::getReportId, reportId)
                        .orderByAsc(EvaluationHumanReview::getCaseSeq)
                        .orderByAsc(EvaluationHumanReview::getId));
        Map<Long, Map<String, Double>> auto = sampleMetricScores(reportId);
        ObjectNode out = json.createObjectNode();
        ObjectNode overall = out.putObject("overall");
        List<Double> humanScores = new ArrayList<>();
        int pass = 0;
        for (EvaluationHumanReview h : rows) {
            if (h.getMetric() != null && !"*".equals(h.getMetric())) {
                continue; // 对比行（有指标）不计入整体黄金标准，只进逐指标对齐
            }
            double s = humanScoreOf(h);
            if (s < 0) {
                continue; // 无人工分（占位）不进整体统计
            }
            humanScores.add(s);
            if (s >= 0.8) {
                pass++;
            }
        }
        overall.put("n", humanScores.size());
        overall.put("meanHuman", humanScores.isEmpty() ? 0.0 : round4(meanOf(humanScores)));
        overall.put("passRate", humanScores.isEmpty() ? 0.0 : round4(pass / (double) humanScores.size()));
        ArrayNode metrics = out.putArray("metrics");
        Map<String, List<EvaluationHumanReview>> byMetric = new LinkedHashMap<>();
        for (EvaluationHumanReview h : rows) {
            if (h.getMetric() != null && !"*".equals(h.getMetric())) {
                byMetric.computeIfAbsent(h.getMetric(), k -> new ArrayList<>()).add(h);
            }
        }
        for (Map.Entry<String, List<EvaluationHumanReview>> e : byMetric.entrySet()) {
            ObjectNode row = metrics.addObject();
            row.put("metric", e.getKey());
            List<EvaluationHumanReview> rs = e.getValue();
            List<Double> humans = new ArrayList<>();
            List<double[]> deltas = new ArrayList<>(); // {|d|, seq, auto, human, delta}
            int agree = 0;
            for (EvaluationHumanReview h : rs) {
                double human = humanScoreOf(h);
                if (human < 0) {
                    continue;
                }
                humans.add(human);
                Double a = sampleScore(auto, h.getCaseSeq(), h.getMetric());
                if (a == null) {
                    continue;
                }
                double d = a - human;
                if (Math.abs(d) < 0.5) {
                    agree++;
                }
                deltas.add(new double[]{Math.abs(d), h.getCaseSeq(), a, human, d});
            }
            row.put("n", humans.size());
            row.put("meanAuto", autoAlignMeanOf(auto, rs));
            row.put("meanHuman", humans.isEmpty() ? 0.0 : round4(meanOf(humans)));
            row.put("meanAbsDiff", autoAbsDiffMeanOf(auto, rs));
            row.put("agreementRate", deltas.isEmpty() ? 0.0 : round4(agree / (double) deltas.size()));
            deltas.sort((a, b) -> Double.compare(b[0], a[0]));
            ArrayNode top = row.putArray("topDeltas");
            for (int k = 0; k < Math.min(10, deltas.size()); k++) {
                double[] d = deltas.get(k);
                ObjectNode t = top.addObject();
                t.put("caseSeq", (long) d[1]);
                t.put("auto", round4(d[2]));
                t.put("human", round4(d[3]));
                t.put("delta", round4(d[4]));
            }
        }
        return out;
    }

    private static double humanScoreOf(EvaluationHumanReview h) {
        return h.getScore() == null ? -1 : h.getScore().doubleValue();
    }

    /** 对齐 auto 均值：仅计入有 auto 分且有人工分的行。 */
    private static double autoAlignMeanOf(Map<Long, Map<String, Double>> auto, List<EvaluationHumanReview> rs) {
        List<Double> as = new ArrayList<>();
        for (EvaluationHumanReview h : rs) {
            if (h.getScore() == null) {
                continue;
            }
            Double a = sampleScore(auto, h.getCaseSeq(), h.getMetric());
            if (a != null) {
                as.add(a);
            }
        }
        return as.isEmpty() ? 0.0 : round4(meanOf(as));
    }

    /** 对齐 |auto-human| 均值（仅计入双侧有分的行）。 */
    private static double autoAbsDiffMeanOf(Map<Long, Map<String, Double>> auto, List<EvaluationHumanReview> rs) {
        List<Double> ds = new ArrayList<>();
        for (EvaluationHumanReview h : rs) {
            if (h.getScore() == null) {
                continue;
            }
            Double a = sampleScore(auto, h.getCaseSeq(), h.getMetric());
            if (a != null) {
                ds.add(Math.abs(a - h.getScore().doubleValue()));
            }
        }
        return ds.isEmpty() ? 0.0 : round4(meanOf(ds));
    }

    // ---------- 驾驶舱看板与复现（P4） ----------

    /**
     * 驾驶舱看板聚合（A4）：layering（最新报告）+ trend（最近 N 条，升序输出，含 summary 得分/
     * 判定 + execution 摘要）+ metrics（核心指标 series/latest/delta）+ regressions（核心指标
     * 趋势内最差回归前 5）+ costLatency（延迟/步数/成本聚合）。无报告 → (null, [], null, null, null)。
     */
    public DashboardView dashboardReport(Long datasetId, Integer limit) {
        Long tenantId = TenantContext.require();
        int lim = limit == null ? DASHBOARD_LIMIT_DEFAULT
                : Math.max(1, Math.min(limit, DASHBOARD_LIMIT_MAX));
        LambdaQueryWrapper<EvaluationReport> w = new LambdaQueryWrapper<>();
        w.eq(EvaluationReport::getTenantId, tenantId);
        if (datasetId != null && datasetId > 0) {
            w.eq(EvaluationReport::getDatasetId, datasetId);
        }
        w.orderByDesc(EvaluationReport::getId).last("LIMIT " + lim);
        List<EvaluationReport> desc = reportMapper.selectList(w);
        if (desc.isEmpty()) {
            return new DashboardView(null, List.of(), null, null, null);
        }
        List<EvaluationReport> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        ArrayNode trend = json.createArrayNode();
        for (EvaluationReport r : asc) {
            ObjectNode t = trend.addObject();
            t.put("id", r.getId());
            t.put("name", r.getName());
            t.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            JsonNode summary = parse(r.getSummary());
            ObjectNode s = t.putObject("summary");
            s.put("score", summary == null || !summary.path("score").isNumber()
                    ? 0 : summary.path("score").asDouble());
            s.put("verdict", summary == null ? null : summary.path("verdict").asText(null));
            JsonNode exec = parse(r.getExecution());
            if (exec != null && exec.isObject()) {
                ObjectNode es = t.putObject("execution");
                es.put("total_duration_ms", exec.path("total_duration_ms").asLong(0));
                es.put("avg_latency_ms", exec.path("avg_latency_ms").isNumber()
                        ? exec.path("avg_latency_ms").asLong() : null);
                es.put("llm_calls", exec.path("llm_calls").isNumber()
                        ? exec.path("llm_calls").asLong() : null);
                es.put("estimated_cost_cny", exec.path("estimated_cost_cny").isNumber()
                        ? exec.path("estimated_cost_cny").asDouble() : null);
            }
        }
        EvaluationReport latest = desc.get(0);
        JsonNode layering = parse(latest.getLayering());
        ObjectNode metrics = json.createObjectNode();
        ArrayNode series = metrics.putArray("series");
        ObjectNode latestObj = metrics.putObject("latest");
        ObjectNode deltaObj = metrics.putObject("delta");
        List<double[]> regDeltas = new ArrayList<>(); // {delta, worstCurrent, worstPrev}
        List<String> regNames = new ArrayList<>();
        for (String m : CORE_METRICS) {
            ObjectNode pt = series.addObject();
            pt.put("metric", m);
            ArrayNode points = pt.putArray("points");
            Double last = null;
            Double prev = null;
            double worst = Double.MAX_VALUE;
            Double worstPrev = null;
            Double worstCur = null;
            for (EvaluationReport r : asc) {
                Double score = metricScoreOf(r, m);
                if (score == null) {
                    continue;
                }
                prev = last;
                last = score;
                ObjectNode p = points.addObject();
                p.put("reportId", r.getId());
                p.put("score", round4(score));
                p.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
                if (prev != null && last != null && last - prev < worst) {
                    worst = last - prev;
                    worstPrev = prev;
                    worstCur = last;
                }
            }
            if (last != null) {
                latestObj.put(m, round4(last));
                if (prev != null) {
                    deltaObj.put(m, round4(last - prev));
                } else {
                    deltaObj.putNull(m);
                }
            } else {
                latestObj.putNull(m);
                deltaObj.putNull(m);
            }
            if (worst < Double.MAX_VALUE && worstCur != null && worstPrev != null) {
                regDeltas.add(new double[]{worst, worstCur, worstPrev});
                regNames.add(m);
            }
        }
        ArrayNode regressions = json.createArrayNode();
        int[] order = sortedIndices(regDeltas, true);
        for (int k = 0; k < Math.min(5, order.length); k++) {
            double[] d = regDeltas.get(order[k]);
            ObjectNode reg = regressions.addObject();
            reg.put("metric", regNames.get(order[k]));
            reg.put("current", round4(d[1]));
            reg.put("previous", round4(d[2]));
            reg.put("delta", round4(d[0]));
        }
        ObjectNode costLatency = json.createObjectNode();
        List<Long> latencies = new ArrayList<>();
        List<Double> steps = new ArrayList<>();
        long totalTokens = 0;
        double cost = 0;
        for (EvaluationReport r : desc) {
            JsonNode exec = parse(r.getExecution());
            if (exec == null || !exec.isObject()) {
                continue;
            }
            if (exec.path("avg_latency_ms").isNumber()) {
                latencies.add(exec.path("avg_latency_ms").asLong());
            }
            if (exec.path("avg_steps").isNumber()) {
                steps.add(exec.path("avg_steps").asDouble());
            }
            totalTokens += exec.path("input_tokens").asLong(0) + exec.path("output_tokens").asLong(0)
                    + exec.path("judge_input_tokens").asLong(0) + exec.path("judge_output_tokens").asLong(0);
            cost += exec.path("estimated_cost_cny").asDouble(0);
        }
        costLatency.put("avg_latency_ms", latencies.isEmpty() ? null
                : Math.round(meanOf(latencies.stream().map(Double::valueOf).toList())));
        costLatency.put("p95_latency_ms", percentile(latencies, 0.95));
        costLatency.put("avg_steps", steps.isEmpty() ? null : round4(meanOf(steps)));
        costLatency.put("total_tokens", totalTokens);
        costLatency.put("cost_cny", round4(cost));
        return new DashboardView(layering, asList(trend), metrics, regressions, costLatency);
    }

    /** 报告单指标均值（无该指标 → null）。 */
    private Double metricScoreOf(EvaluationReport r, String metric) {
        JsonNode metrics = parse(r.getMetrics());
        if (metrics == null || !metrics.isArray()) {
            return null;
        }
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText()) && m.path("avg_score").isNumber()) {
                return m.path("avg_score").asDouble();
            }
        }
        return null;
    }

    private static List<JsonNode> asList(ArrayNode arr) {
        List<JsonNode> list = new ArrayList<>();
        arr.forEach(list::add);
        return list;
    }

    /**
     * 复现（T3）：以原报告的版本快照 + 评测器集 + judgeRounds 重跑，生成新报告；
     * 新报告 summary.baseline_report_id = 原报告 id（自带基线）。原报告未绑版本 → 400。
     */
    @Transactional
    public ReportView rerunReport(Long id, String operator) {
        Long tenantId = TenantContext.require();
        EvaluationReport r = requireReport(id, tenantId);
        if (r.getDatasetVersionId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "原报告未绑定数据集版本快照，无法复现（请先发布数据集版本）");
        }
        requireVersion(r.getDatasetVersionId(), tenantId, r.getDatasetId());
        List<String> metrics = new ArrayList<>();
        JsonNode m = parse(r.getMetrics());
        if (m != null && m.isArray()) {
            m.forEach(x -> {
                String name = x.path("metric").asText("");
                if (!name.isEmpty()) {
                    metrics.add(name);
                }
            });
        }
        ReportView view = run(new EvaluationRunRequest(r.getDatasetId(), metrics,
                r.getJudgeRounds(), r.getDatasetVersionId()), operator);
        EvaluationReport nr = reportMapper.selectById(view.id());
        ObjectNode summary = parse(nr.getSummary()) instanceof ObjectNode so
                ? (ObjectNode) so.deepCopy() : json.createObjectNode();
        summary.put("baseline_report_id", id);
        nr.setSummary(writeOrNull(summary));
        reportMapper.updateById(nr);
        return toReportView(nr);
    }

    // ---------- 评测目录（H6） ----------

    /** rag_hit_rate 目录描述（execute 专属语义指标之一）。 */
    private static final String RAG_HIT_RATE_DESC =
            "execute 专属：本系统智能体 search_kb 知识库检索命中率，需知识库已有文档且用例配 expectedKbHits";

    /** decision_accuracy 目录描述：首意图决策准确率（首个工具调用与期望工具/参数匹配度）。 */
    private static final String DECISION_ACCURACY_DESC =
            "execute 专属：首意图决策准确率——首个工具调用与期望工具/参数匹配度（命中工具 0.5，参数全匹配 1.0）";

    /** 内置指标静态元数据 + 启用的自定义评测器；均 higherIsBetter / 通过线 0.8。 */
    public List<MetricCatalogView> catalog() {
        Long tenantId = TenantContext.require();
        List<MetricCatalogView> list = new ArrayList<>();
        for (String metric : EvaluationModel.ALL_METRICS) {
            list.add(new MetricCatalogView(metric,
                    EvaluationModel.RULE_METRICS.contains(metric) ? "rule" : "llm_judge",
                    true, 0.8, metricDesc(metric)));
        }
        for (EvaluationCustomEvaluator ce : customEvaluatorMapper.selectList(
                new LambdaQueryWrapper<EvaluationCustomEvaluator>()
                        .eq(EvaluationCustomEvaluator::getTenantId, tenantId)
                        .eq(EvaluationCustomEvaluator::getStatus, "ENABLED")
                        .orderByAsc(EvaluationCustomEvaluator::getId))) {
            list.add(new MetricCatalogView(ce.metric(), ce.getCategory(), true, 0.8, null));
        }
        return list;
    }

    /** 内置指标目录描述（execute 专属语义指标带说明；其余 null）。 */
    private static String metricDesc(String metric) {
        if ("rag_hit_rate".equals(metric)) {
            return RAG_HIT_RATE_DESC;
        }
        if ("decision_accuracy".equals(metric)) {
            return DECISION_ACCURACY_DESC;
        }
        return null;
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
     * execute 用例执行：以唯一 sessionId 运行被测智能体（防会话状态串扰），多轮对话以同一
     * sessionId 连续调用（AgentState 跨轮保留，见 {@link #turnsOf}），成功收集每轮实际响应
     * （dialogue_responses）+ 工具调用轨迹 + 逐轮转录 + 整例耗时 latency_ms。
     * 失败/超时/空回复该用例不注入 actual_response（判分器视为不适用，产出 INFO 发现，不中断整轮）。
     */
    private void executeSubject(ObjectNode n, ReActAgent subject, Long tenantId, String operator,
                                EvaluationCase c, String traceId, List<EvaluationTranscript> transcripts) {
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
        Instant started = Instant.now();
        try {
            // 多轮执行：首轮 question，其后按 dialogue 中 user 追问逐轮追加（同一 ctx/sessionId）
            List<String> turns = turnsOf(c);
            ArrayNode dialogueResponses = json.createArrayNode();
            String finalText = null;
            int serialized = 0; // 上下文已转录消息数（增量：每轮仅序列化本轮新增消息）
            for (int i = 0; i < turns.size(); i++) {
                Msg result = subject.call(List.of(new UserMessage(turns.get(i))), ctx)
                        .block(EXECUTE_TIMEOUT);
                String text = result == null ? null : result.getTextContent();
                if (text != null && !text.isBlank()) {
                    dialogueResponses.add(text);
                    finalText = text;
                }
                // 转录本轮新增消息（turn_no = 1-based 调用序号），逐条 try/catch 绝不中断执行
                serialized = collectTranscripts(transcripts, c, i + 1, userId, sessionId,
                        subject, serialized);
            }
            if (finalText == null || finalText.isBlank()) {
                log.warn("评测 execute 用例 seq={} 返回空回复，判分跳过（不适用）", c.getSeq());
                return;
            }
            n.put("actual_response", finalText);
            if (dialogueResponses.size() > 1) {
                n.set("dialogue_responses", dialogueResponses); // 多轮判分输入（judgeResponse 拼接）
            }

            // 轨迹：从会话状态上下文收集已执行工具调用（ASKING/PENDING 未执行不计入）
            List<ToolUseBlock> executed = new ArrayList<>();
            AgentState state = subject.getAgentState(userId, sessionId);
            List<Msg> ctxMsgs = state == null || state.getContext() == null
                    ? List.of() : state.getContext();
            for (Msg m : ctxMsgs) {
                for (ToolUseBlock tub : m.getContentBlocks(ToolUseBlock.class)) {
                    if (tub.getState() == ToolCallState.ASKING
                            || tub.getState() == ToolCallState.PENDING) {
                        continue;
                    }
                    executed.add(tub);
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
            // rag_hit_rate 判分输入：工具结果快照（name/state/output），output 取首个文本块截 8000 防爆；
            // 不按 state 过滤（判分侧只看 name=search_kb 且 output 可解析为 KbSearchView JSON）
            ArrayNode results = json.createArrayNode();
            for (Msg m : ctxMsgs) {
                for (ToolResultBlock trb : m.getContentBlocks(ToolResultBlock.class)) {
                    ObjectNode r = results.addObject();
                    r.put("name", trb.getName() == null ? "" : trb.getName());
                    r.put("state", trb.getState() == null ? "" : trb.getState().getValue());
                    String output = "";
                    if (trb.getOutput() != null) {
                        for (var cb : trb.getOutput()) {
                            if (cb instanceof io.agentscope.core.message.TextBlock tb) {
                                output = tb.getText();
                                break;
                            }
                        }
                    }
                    if (output.length() > 8000) {
                        output = output.substring(0, 8000);
                    }
                    r.put("output", output);
                }
            }
            n.set("actual_tool_results", results);
            log.info("评测 execute seq={} 回复=[{}] calls={}", c.getSeq(), finalText, calls);
        } catch (Exception e) {
            log.warn("评测 execute 用例 seq={} 执行失败（{}），判分跳过（不适用）", c.getSeq(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            // 整例执行耗时（全部轮次，毫秒）：仅 execute 路径写入；openjudge 无此键
            n.put("latency_ms", Duration.between(started, Instant.now()).toMillis());
        }
    }

    /**
     * 执行轮次：question 为首轮固定追问；dialogue（{[role, content]} 数组）中 user 消息按序
     * 追加为后续轮（assistant 回填不入输入）。首条 user 与 question 相同 → 去重（不重复提问）。
     */
    private List<String> turnsOf(EvaluationCase c) {
        List<String> turns = new ArrayList<>();
        turns.add(c.getQuestion());
        JsonNode dialogue = parse(c.getDialogue());
        if (dialogue == null || !dialogue.isArray()) {
            return turns;
        }
        for (JsonNode m : dialogue) {
            if (!"user".equals(m.path("role").asText(""))) {
                continue;
            }
            String content = m.path("content").asText("");
            if (content.isBlank()) {
                continue;
            }
            if (turns.size() == 1 && content.equals(c.getQuestion())) {
                continue; // 首条 user 即 question 本身 → 去重
            }
            turns.add(content);
        }
        return turns;
    }

    /**
     * 转录增量采集：对会话上下文中 [from, size) 新增消息按 轮次 turnNo 序列化入缓冲。
     * USER → text（+thinking 首个 ThinkingBlock，各截 4000）；ASSISTANT → 含 ToolUseBlock
     * 的工具调用消息产出 tool_use {name,args} 行（agentscope 2.0.2 工具调用挂 ASSISTANT
     * 消息），否则纯文本行；TOOL → tool_use 或 tool_result {name,state,output 截 8000}
     * （同消息可两条）。逐条 try/catch，序列化失败仅降级该条，绝不中断多轮执行；返回新的已转录消息数。
     */
    private int collectTranscripts(List<EvaluationTranscript> out, EvaluationCase c, int turnNo,
                                   String userId, String sessionId, ReActAgent subject, int from) {
        if (out == null) {
            return from;
        }
        try {
            AgentState state = subject.getAgentState(userId, sessionId);
            List<Msg> ctx = state == null || state.getContext() == null ? null : state.getContext();
            if (ctx == null) {
                return from;
            }
            int size = ctx.size();
            for (int i = from; i < size; i++) {
                Msg m = ctx.get(i);
                if (m == null) {
                    continue;
                }
                try {
                    String role = m.getRole() == null ? null : m.getRole().name();
                    List<ToolUseBlock> toolUses = m.getContentBlocks(ToolUseBlock.class);
                    if ("TOOL".equals(role) || !toolUses.isEmpty()) {
                        for (ToolUseBlock tub : toolUses) {
                            EvaluationTranscript row = new EvaluationTranscript();
                            row.setTenantId(c.getTenantId());
                            row.setCaseSeq(c.getSeq());
                            row.setTurnNo(turnNo);
                            row.setRole("TOOL");
                            ObjectNode tu = json.createObjectNode();
                            tu.put("name", tub.getName() == null ? "" : tub.getName());
                            if (tub.getInput() != null) {
                                tu.set("args", json.valueToTree(tub.getInput()));
                            }
                            row.setToolUse(clip(tu.toString(), 4000));
                            out.add(row);
                        }
                    }
                    if ("TOOL".equals(role)) {
                        for (ToolResultBlock trb : m.getContentBlocks(ToolResultBlock.class)) {
                            EvaluationTranscript row = new EvaluationTranscript();
                            row.setTenantId(c.getTenantId());
                            row.setCaseSeq(c.getSeq());
                            row.setTurnNo(turnNo);
                            row.setRole(role);
                            ObjectNode tr = json.createObjectNode();
                            tr.put("name", trb.getName() == null ? "" : trb.getName());
                            tr.put("state", trb.getState() == null ? "" : trb.getState().name());
                            String output = "";
                            if (trb.getOutput() != null) {
                                for (var cb : trb.getOutput()) {
                                    if (cb instanceof io.agentscope.core.message.TextBlock tb) {
                                        output = tb.getText();
                                        break;
                                    }
                                }
                            }
                            tr.put("output", clip(output, 8000));
                            row.setToolResult(clip(tr.toString(), 8000));
                            out.add(row);
                        }
                    }
                    if ("USER".equals(role) || "ASSISTANT".equals(role)) {
                        String viewText = m.getTextContent();
                        // 工具调用壳消息（ASSISTANT + ToolUseBlock）不落文本行：其文本为内部导语，
                        // 用户可见回复是轮末独立文本消息；tool_use 行已在上方产出。
                        if (viewText == null || viewText.isBlank()
                                || ("ASSISTANT".equals(role) && !toolUses.isEmpty())) {
                            continue;
                        }
                        EvaluationTranscript row = new EvaluationTranscript();
                        row.setTenantId(c.getTenantId());
                        row.setCaseSeq(c.getSeq());
                        row.setTurnNo(turnNo);
                        row.setRole(role);
                        row.setText(clip(viewText, 4000));
                        row.setThinking(clip(firstThinking(m), 4000));
                        out.add(row);
                    }
                    // SYSTEM 等其余角色（系统提示/工具定义指令）不转录
                } catch (Exception e) {
                    log.warn("评测 execute seq={} 转录第 {} 条序列化失败（跳过）: {}", c.getSeq(),
                            i, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
            return size;
        } catch (Exception e) {
            log.warn("评测 execute seq={} 转录采集失败（跳过）: {}", c.getSeq(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return from;
        }
    }

    /** 消息中首个非空 ThinkingBlock 文本，无则 null。 */
    private static String firstThinking(Msg m) {
        for (ThinkingBlock tb : m.getContentBlocks(ThinkingBlock.class)) {
            String t = tb.getThinking();
            if (t != null && !t.isBlank()) {
                return t;
            }
        }
        return null;
    }

    /** 截断：null 保留 null，超长截前 max 字符（防爆转录/判分输入）。 */
    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
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
            if (judgeResponse(cn).isEmpty()) {
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
                JsonNode rule = judgeRuleFor(cn, metric);
                String prompt = rule != null && rule.path("judge_prompt").isTextual()
                        && !rule.path("judge_prompt").asText().isBlank()
                        ? rule.path("judge_prompt").asText()
                        : (custom != null ? custom.getJudgePrompt() : LlmJudgeScorer.defaultPrompt(metric));
                int taskRounds = rule != null && rule.path("rounds").isInt()
                        ? Math.max(1, Math.min(rule.path("rounds").asInt(), 5)) : rounds;
                tasks.add(new JudgeTask(caseIndex, cn, metric, prompt,
                        text(cn.path("question")), judgeResponse(cn),
                        text(cn.path("expected_output")), taskRounds));
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
                            t.expected(), t.rounds(), tenantId, traceId), JUDGE_POOL));
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

    /** 一条 LLM-Judge 判分任务（并行提交，写回按原顺序；caseIndex 对齐 cases[] 下标；
     * rounds = 用例级 judge_rule.rounds 覆盖，缺省运行级 rounds）。 */
    private record JudgeTask(int caseIndex, JsonNode caseNode, String metric, String prompt,
                             String question, String response, String expected, int rounds) {
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

    /**
     * 判分实际响应取数：多轮用例（dialogue_responses 数组）逐轮拼接（"[turn N] " 前缀 + 截 2000，
     * 总截 8000，供 LLM-Judge 看完整对话轨迹）；单轮/未多轮回退 actual_response（历史行为不变）。
     */
    private static String judgeResponse(JsonNode cn) {
        JsonNode dr = cn.path("dialogue_responses");
        if (!dr.isArray() || dr.isEmpty()) {
            return text(cn.path("actual_response"));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dr.size(); i++) {
            String t = dr.get(i).asText("");
            if (t == null || t.isBlank()) {
                continue;
            }
            sb.append("[turn ").append(i + 1).append("] ")
                    .append(clip(t, 2000)).append('\n');
        }
        String joined = sb.toString();
        return joined.length() > 8000 ? joined.substring(0, 8000) : joined;
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
        return toDatasetView(d, latestVersion(d.getId()), categoryCounts(d.getId()));
    }

    /** 批量视图：最新版本 + 三档计数直接透传（listDatasets 用，避免 N+1）。 */
    private DatasetView toDatasetView(EvaluationDataset d, EvaluationDatasetVersion latest,
                                      long[] categoryCounts) {
        int caseCount = categoryCounts == null ? 0
                : Math.toIntExact(categoryCounts[0] + categoryCounts[1] + categoryCounts[2]);
        return new DatasetView(d.getId(), d.getName(), d.getDescription(), d.getScope(), d.getMode(),
                d.getAgentType(), d.getStatus(), caseCount,
                latest == null ? null : latest.getId(),
                latest == null ? null : latest.getVersionNo(),
                categoryCountObject(categoryCounts),
                d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedAt());
    }

    private CaseView toCaseView(EvaluationCase c) {
        return new CaseView(c.getId(), c.getDatasetId(), c.getSeq(), c.getQuestion(),
                c.getSystemPrompt(), parse(c.getExpectedOutput()), parse(c.getToolSchema()),
                parse(c.getExpectedTool()), c.getExpectedSteps(), parse(c.getExpectedPolicy()),
                parse(c.getExpectedKbHits()), c.getProvidedResponse(), c.getCategory(),
                parse(c.getJudgeRule()), parse(c.getDialogue()), c.getCreatedAt());
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
                parse(r.getExecution()), parse(r.getEnvSnapshot()), parse(r.getCodeSnapshot()),
                parse(r.getLayering()),
                r.getConfidence(), r.getModel(), r.getMode(),
                r.getJudgeRounds(), r.getTraceId(), r.getDatasetVersionId(), r.getDatasetVersionNo(),
                r.getCreatedBy(), r.getCreatedAt());
    }

    // ---------- 数据集版本（P0/P1） ----------

    /** 版本解析统一入口：显式版本 → 校验（租户/归属 404）；缺省 → 最新 PUBLISHED，无则回退实时用例。 */
    private UsedVersion resolveVersion(EvaluationDataset d, Long requestedVersionId, Long tenantId) {
        if (requestedVersionId != null) {
            EvaluationDatasetVersion v = requireVersion(requestedVersionId, tenantId, d.getId());
            return new UsedVersion(v.getId(), v.getVersionNo(), snapshotCases(v.getCases()));
        }
        EvaluationDatasetVersion latest = latestVersion(d.getId());
        if (latest != null) {
            return new UsedVersion(latest.getId(), latest.getVersionNo(), snapshotCases(latest.getCases()));
        }
        return new UsedVersion(null, null, loadCases(d.getId()));
    }

    private EvaluationDatasetVersion requireVersion(Long id, Long tenantId, Long datasetId) {
        EvaluationDatasetVersion v = versionMapper.selectById(id);
        if (v == null || !tenantId.equals(v.getTenantId()) || !datasetId.equals(v.getDatasetId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集版本不存在: " + id);
        }
        return v;
    }

    /** 单数据集最新 PUBLISHED 版本（无 → null）。 */
    private EvaluationDatasetVersion latestVersion(Long datasetId) {
        return versionMapper.selectList(new LambdaQueryWrapper<EvaluationDatasetVersion>()
                        .eq(EvaluationDatasetVersion::getDatasetId, datasetId)
                        .eq(EvaluationDatasetVersion::getStatus, "PUBLISHED")
                        .orderByDesc(EvaluationDatasetVersion::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 多数据集最新 PUBLISHED 版本（每个数据集仅最高 version_no 行；无版本数据集缺席）。 */
    private Map<Long, EvaluationDatasetVersion> latestVersionByDataset(List<Long> datasetIds) {
        List<EvaluationDatasetVersion> all = versionMapper.selectList(new LambdaQueryWrapper<EvaluationDatasetVersion>()
                .eq(EvaluationDatasetVersion::getStatus, "PUBLISHED")
                .in(EvaluationDatasetVersion::getDatasetId, datasetIds)
                .orderByAsc(EvaluationDatasetVersion::getVersionNo));
        Map<Long, EvaluationDatasetVersion> latest = new LinkedHashMap<>();
        for (EvaluationDatasetVersion v : all) {
            EvaluationDatasetVersion prev = latest.get(v.getDatasetId());
            if (prev == null || prev.getVersionNo() < v.getVersionNo()) {
                latest.put(v.getDatasetId(), v);
            }
        }
        return latest;
    }

    /** 单数据集三档计数 [basic, edge, real]。 */
    private long[] categoryCounts(Long datasetId) {
        return aggregateCategoryCounts(caseMapper.selectMaps(new QueryWrapper<EvaluationCase>()
                .select("dataset_id AS dsid", "category AS cat", "COUNT(*) AS cnt")
                .eq("dataset_id", datasetId)
                .groupBy("dataset_id", "category")));
    }

    /** 多数据集三档计数（Map<datasetId, long[3]>；无用例数据集缺席 → 调用侧兜底空档）。 */
    private Map<Long, long[]> categoryCountsByDataset(List<Long> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = caseMapper.selectMaps(new QueryWrapper<EvaluationCase>()
                .select("dataset_id AS dsid", "category AS cat", "COUNT(*) AS cnt")
                .in("dataset_id", datasetIds)
                .groupBy("dataset_id", "category"));
        Map<Long, long[]> byDataset = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long dsid = toLong(row.get("dsid"));
            if (dsid == null) {
                continue;
            }
            long[] counts = byDataset.computeIfAbsent(dsid, k -> new long[3]);
            int idx = categoryIndex(String.valueOf(row.get("cat")));
            counts[idx] += toLong(row.get("cnt"));
        }
        return byDataset;
    }

    private long[] aggregateCategoryCounts(List<Map<String, Object>> rows) {
        long[] counts = new long[3];
        for (Map<String, Object> row : rows) {
            int idx = categoryIndex(String.valueOf(row.get("cat")));
            counts[idx] += toLong(row.get("cnt"));
        }
        return counts;
    }

    private int categoryIndex(String category) {
        if ("edge".equals(category)) {
            return 1;
        }
        if ("real".equals(category)) {
            return 2;
        }
        return 0;
    }

    private JsonNode categoryCountObject(long[] counts) {
        if (counts == null) {
            counts = new long[3];
        }
        ObjectNode obj = json.createObjectNode();
        obj.put("basic", counts[0]);
        obj.put("edge", counts[1]);
        obj.put("real", counts[2]);
        return obj;
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void validateCategory(String category) {
        String c = category.trim();
        if (!"basic".equals(c) && !"edge".equals(c) && !"real".equals(c)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "非法用例分层 category（basic/edge/real）: " + category);
        }
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "basic";
        }
        return category.trim();
    }

    /** 工作区用例 → 快照 JSON（13 键全含，NULL 显式 JSON null；seq 升序 = loadCases 顺序）。 */
    private String buildSnapshot(List<EvaluationCase> cases) {
        ArrayNode arr = json.createArrayNode();
        for (EvaluationCase c : cases) {
            ObjectNode n = arr.addObject();
            n.put("seq", c.getSeq());
            n.put("category", normalizeCategory(c.getCategory()));
            setOrNull(n, "judge_rule", c.getJudgeRule());
            n.put("question", c.getQuestion());
            putTextOrNull(n, "system_prompt", c.getSystemPrompt());
            putRawOrNull(n, "dialogue", c.getDialogue());
            putRawOrNull(n, "expected_output", c.getExpectedOutput());
            putRawOrNull(n, "tool_schema", c.getToolSchema());
            putRawOrNull(n, "expected_tool", c.getExpectedTool());
            n.put("expected_steps", c.getExpectedSteps() == null ? 1 : c.getExpectedSteps());
            putRawOrNull(n, "expected_policy", c.getExpectedPolicy());
            putRawOrNull(n, "expected_kb_hits", c.getExpectedKbHits());
            putTextOrNull(n, "provided_response", c.getProvidedResponse());
        }
        return writeOrNull(arr);
    }

    /** put 文本字段（null → JSON null，与回填 jsonb_build_object 的 null 语义一致）。 */
    private void putTextOrNull(ObjectNode n, String field, String value) {
        if (value == null) {
            n.putNull(field);
        } else {
            n.put(field, value);
        }
    }

    /** put JSON 原文字段（null → JSON null）。 */
    private void putRawOrNull(ObjectNode n, String field, String raw) {
        JsonNode node = parse(raw);
        if (node == null) {
            n.putNull(field);
        } else {
            n.set(field, node);
        }
    }

    private boolean jsonEquals(String a, String b) {
        JsonNode na = parse(a);
        JsonNode nb = parse(b);
        return na != null && nb != null && na.equals(nb);
    }

    /** 快照 JSON → 实体用例（seq 升序，与回填一致）。 */
    private List<EvaluationCase> snapshotCases(String snapshot) {
        JsonNode arr = parse(snapshot);
        List<EvaluationCase> cases = new ArrayList<>();
        if (arr == null) {
            return cases;
        }
        arr.forEach(n -> {
            EvaluationCase c = caseFromSnapshot(n);
            if (c != null) {
                cases.add(c);
            }
        });
        return cases;
    }

    private EvaluationCase caseFromSnapshot(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        EvaluationCase c = new EvaluationCase();
        c.setSeq(n.path("seq").isValueNode() ? n.path("seq").asInt() : null);
        c.setQuestion(n.path("question").isValueNode() ? n.path("question").asText() : null);
        c.setSystemPrompt(nodeText(n, "system_prompt"));
        c.setCategory(nodeText(n, "category") == null ? "basic" : nodeText(n, "category"));
        c.setJudgeRule(nodeOrNull(n, "judge_rule"));
        c.setDialogue(nodeOrNull(n, "dialogue"));
        c.setExpectedOutput(nodeOrNull(n, "expected_output"));
        c.setToolSchema(nodeOrNull(n, "tool_schema"));
        c.setExpectedTool(nodeOrNull(n, "expected_tool"));
        c.setExpectedSteps(n.path("expected_steps").isValueNode() ? n.path("expected_steps").asInt() : 1);
        c.setExpectedPolicy(nodeOrNull(n, "expected_policy"));
        c.setExpectedKbHits(nodeOrNull(n, "expected_kb_hits"));
        c.setProvidedResponse(nodeText(n, "provided_response"));
        return c;
    }

    /** 快照 JSON → CaseView 列表（dataset 归属）。 */
    private List<CaseView> snapshotToCaseViews(String snapshot, Long datasetId) {
        List<CaseView> views = new ArrayList<>();
        for (EvaluationCase c : snapshotCases(snapshot)) {
            views.add(new CaseView(null, datasetId, c.getSeq(), c.getQuestion(), c.getSystemPrompt(),
                    parse(c.getExpectedOutput()), parse(c.getToolSchema()), parse(c.getExpectedTool()),
                    c.getExpectedSteps(), parse(c.getExpectedPolicy()), parse(c.getExpectedKbHits()),
                    c.getProvidedResponse(), c.getCategory(), parse(c.getJudgeRule()),
                    parse(c.getDialogue()), null));
        }
        return views;
    }

    /** JSON 字段 → 原文 JSON 字符串（null/缺失 → null）。 */
    private String nodeOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本字段（null/缺失 → null）。 */
    private String nodeText(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    private DatasetVersionView toVersionView(EvaluationDatasetVersion v) {
        return new DatasetVersionView(v.getId(), v.getDatasetId(), v.getVersionNo(), v.getStatus(),
                snapshotCases(v.getCases()).size(), v.getPublishedAt(), v.getCreatedBy());
    }

    /**
     * 分层校验（P0）：≥5 用例时按参与用例三档占比校验（目标 basic=40/edge=30/real=30）。
     * 任一档占比 <20% → 追加 1 条 WARNING「layering」发现；<5 例跳过（1/5=20% 不触发）。
     * 既有分级发现保留，仅追加不替换。run/异步任务在聚合 output 后、审计前调用，
     * 保证审计与落库报告一致。
     */
    private void appendLayeringFinding(JsonNode output, List<EvaluationCase> cases) {
        if (output == null || !output.isObject() || cases == null || cases.size() < 5) {
            return;
        }
        long basic = 0;
        long edge = 0;
        long real = 0;
        for (EvaluationCase c : cases) {
            switch (normalizeCategory(c.getCategory())) {
                case "edge" -> edge++;
                case "real" -> real++;
                default -> basic++;
            }
        }
        int total = basic + edge + real > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) (basic + edge + real);
        if (total < 5) {
            return;
        }
        double basicRatio = basic / (double) total;
        double edgeRatio = edge / (double) total;
        double realRatio = real / (double) total;
        if (basicRatio >= 0.2 && edgeRatio >= 0.2 && realRatio >= 0.2) {
            return;
        }
        ((ObjectNode) output).withArray("findings").add(json.createObjectNode()
                .put("level", "WARNING")
                .put("dimension", "layering")
                .put("detail", "分层偏差：参与用例 basic=" + basic + "/edge=" + edge + "/real=" + real
                        + "（目标 40/30/30），存在占比低于 20% 的分层缺失")
                .put("suggestion", "补充相应分层的用例（basic/edge/real），使三档占比不低于 20%"));
    }

    /** 版本解析结果：显式/缺省最新 → versionId/versionNo 非 null + 快照用例；无版本回退 → null + 实时用例。 */
    private record UsedVersion(Long versionId, Integer versionNo, List<EvaluationCase> cases) {
    }
}