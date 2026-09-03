package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.agent.AgentOutcome;
import com.easysys.agent.AgentPolicy;
import com.easysys.agent.AgentRunConfig;
import com.easysys.agent.EvaluationModel;
import com.easysys.api.config.AgentLlmProperties;
import com.easysys.api.dto.evaluation.CaseView;
import com.easysys.api.dto.evaluation.DatasetView;
import com.easysys.api.dto.evaluation.EvaluationRunRequest;
import com.easysys.api.dto.evaluation.ReportView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.EvaluationCase;
import com.easysys.api.entity.EvaluationDataset;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.EvaluationCaseMapper;
import com.easysys.api.mapper.EvaluationDatasetMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 评测中心：数据集 + 用例 + 批量运行（AgentPolicy 确定性评测模型 + 审计）+ 报告回看。
 *
 * <p>运行模式：openjudge 用用例预置响应（provided_response）直接判分，跳过被测智能体执行；
 * execute 模式需要被测智能体链路接入（当前阶段未接入，运行时报错提示，见 {@link #run}）。
 * 评测器为代码内置常量目录（11 个），数据集模式/范围决定判分输入。</p>
 */
@Service
public class EvaluationService {

    private final EvaluationDatasetMapper datasetMapper;
    private final EvaluationCaseMapper caseMapper;
    private final EvaluationReportMapper reportMapper;
    private final AgentAuditMapper auditMapper;
    private final HarnessAgent evaluationAgent;
    private final AgentLlmProperties llm;
    private final ObjectMapper json;

    public EvaluationService(EvaluationDatasetMapper datasetMapper, EvaluationCaseMapper caseMapper,
                             EvaluationReportMapper reportMapper, AgentAuditMapper auditMapper,
                             HarnessAgent evaluationAgent, AgentLlmProperties llm, ObjectMapper json) {
        this.datasetMapper = datasetMapper;
        this.caseMapper = caseMapper;
        this.reportMapper = reportMapper;
        this.auditMapper = auditMapper;
        this.evaluationAgent = evaluationAgent;
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

    // ---------- 批量运行 ----------

    /**
     * 批量运行评测：读数据集+用例 → 组装判分输入 → AgentPolicy.run(EVALUATION) → audit_log →
     * evaluation_report 落库。
     *
     * <p>openjudge：actual_response = 用例预置响应（跳过被测智能体执行）；
     * execute：需要先运行被测智能体取实际输出，被测链路当前未接入，拒绝执行。</p>
     */
    @Transactional
    public ReportView run(EvaluationRunRequest req, String operator) {
        Long tenantId = TenantContext.require();
        if (req.datasetId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "datasetId 不能为空");
        }
        EvaluationDataset d = requireDataset(req.datasetId(), tenantId);
        if ("execute".equals(d.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "execute 模式需要被测智能体链路接入（当前未接入），请使用 openjudge 模式（数据集预置响应判分）");
        }
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

        ObjectNode input = json.createObjectNode();
        input.put("scope", d.getScope());
        input.put("mode", d.getMode());
        input.put("llm_enabled", llm.isEnabled());
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
            if (c.getProvidedResponse() != null) {
                n.put("provided_response", c.getProvidedResponse());
                n.put("actual_response", c.getProvidedResponse()); // openjudge：预置响应直接判分
            }
        }
        ArrayNode evals = input.putArray("evaluators");
        if (req.evaluators() != null && !req.evaluators().isEmpty()) {
            for (String metric : req.evaluators()) {
                if (EvaluationModel.ALL_METRICS.contains(metric)) {
                    ObjectNode e = evals.addObject();
                    e.put("metric", metric);
                    e.put("category", EvaluationModel.RULE_METRICS.contains(metric) ? "rule" : "llm_judge");
                }
            }
        }

        EvaluationModel planner = new EvaluationModel();
        AgentOutcome outcome = AgentPolicy.run(evaluationAgent, planner, planner,
                "evaluation_run", input, AgentRunConfig.defaults());
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

    private void validateDataset(DatasetView.SaveRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集名称不能为空");
        }
        String scope = req.scope() == null ? "llm_call" : req.scope();
        String mode = req.mode() == null ? "openjudge" : req.mode();
        if (!"llm_call".equals(scope)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 scope（仅 llm_call）: " + scope);
        }
        if (!"openjudge".equals(mode) && !"execute".equals(mode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 mode（openjudge/execute）: " + mode);
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
                d.getStatus(), count == null ? 0 : count.intValue(), d.getCreatedBy(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private CaseView toCaseView(EvaluationCase c) {
        return new CaseView(c.getId(), c.getDatasetId(), c.getSeq(), c.getQuestion(),
                c.getSystemPrompt(), parse(c.getExpectedOutput()), parse(c.getToolSchema()),
                parse(c.getExpectedTool()), c.getProvidedResponse(), c.getCreatedAt());
    }

    private ReportView toReportView(EvaluationReport r) {
        return new ReportView(r.getId(), r.getDatasetId(), r.getName(), r.getTotalCases(),
                r.getTestedCases(), parse(r.getMetrics()), parse(r.getFindings()), parse(r.getSummary()),
                r.getConfidence(), r.getModel(), r.getMode(), r.getCreatedBy(), r.getCreatedAt());
    }
}