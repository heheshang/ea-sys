package com.easysys.api.controller;

import com.easysys.api.dto.evaluation.CaseView;
import com.easysys.api.dto.evaluation.CustomEvaluatorView;
import com.easysys.api.dto.evaluation.DashboardView;
import com.easysys.api.dto.evaluation.DatasetView;
import com.easysys.api.dto.evaluation.DatasetVersionView;
import com.easysys.api.dto.evaluation.EvaluationRunRequest;
import com.easysys.api.dto.evaluation.HumanReviewView;
import com.easysys.api.dto.evaluation.PublishVersionRequest;
import com.easysys.api.dto.evaluation.ImportResultView;
import com.easysys.api.dto.evaluation.MetricCatalogView;
import com.easysys.api.dto.evaluation.ReportCompareView;
import com.easysys.api.dto.evaluation.ReportView;
import com.easysys.api.dto.evaluation.TaskDetailView;
import com.easysys.api.dto.evaluation.TaskView;
import com.easysys.api.dto.evaluation.TranscriptView;
import com.easysys.api.service.EvaluationService;
import com.easysys.common.web.ApiResponse;
import com.easysys.common.web.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.easysys.common.web.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测中心：数据集 + 用例管理，批量运行（AgentPolicy 确定性评测模型 + 审计）、
 * 异步任务（H1 任务状态机 + 逐样本结果）、报告对比（H4）与评测目录（H6）。
 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    // ---------- 数据集 ----------

    @GetMapping("/datasets")
    public ApiResponse<List<DatasetView>> datasets() {
        return ApiResponse.ok(evaluationService.listDatasets());
    }

    @PostMapping("/datasets")
    public ApiResponse<DatasetView> createDataset(@Valid @RequestBody DatasetView.SaveRequest req,
                                                  @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.createDataset(req, username));
    }

    @PutMapping("/datasets/{id}")
    public ApiResponse<DatasetView> updateDataset(@PathVariable Long id,
                                                  @Valid @RequestBody DatasetView.SaveRequest req,
                                                  @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.updateDataset(id, req, username));
    }

    @DeleteMapping("/datasets/{id}")
    public ApiResponse<Void> deleteDataset(@PathVariable Long id, @RequestAttribute String username) {
        evaluationService.deleteDataset(id);
        return ApiResponse.ok(null);
    }

    // ---------- 用例 ----------

    @GetMapping("/datasets/{id}/cases")
    public ApiResponse<List<CaseView>> cases(@PathVariable("id") Long datasetId) {
        return ApiResponse.ok(evaluationService.listCases(datasetId));
    }

    @PostMapping("/datasets/{id}/cases")
    public ApiResponse<CaseView> addCase(@PathVariable("id") Long datasetId,
                                         @Valid @RequestBody CaseView.SaveRequest req,
                                         @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.addCase(datasetId, req, username));
    }

    /** 数据集 jsonl 批量导入（body 为逐行 JSON 或整体 JSON 数组；坏行跳过返回明细）。 */
    @PostMapping("/datasets/{id}/import")
    public ApiResponse<ImportResultView> importCases(@PathVariable("id") Long datasetId,
                                                     @RequestBody String content,
                                                     @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.importCases(datasetId, content, username));
    }

    @PutMapping("/cases/{id}")
    public ApiResponse<CaseView> updateCase(@PathVariable Long id,
                                            @Valid @RequestBody CaseView.SaveRequest req,
                                            @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.updateCase(id, req, username));
    }

    @DeleteMapping("/cases/{id}")
    public ApiResponse<Void> deleteCase(@PathVariable Long id, @RequestAttribute String username) {
        evaluationService.deleteCase(id);
        return ApiResponse.ok(null);
    }

    // ---------- 数据集版本（P0/P1） ----------

    /** 发布版本：当前工作区用例快照落版本；内容与最新版本一致 → 400。 */
    @PostMapping("/datasets/{id}/versions")
    public ApiResponse<DatasetVersionView> publishVersion(@PathVariable("id") Long datasetId,
                                                          @RequestBody(required = false) PublishVersionRequest req,
                                                          @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.publishVersion(datasetId, req, username));
    }

    /** 版本列表（version_no 倒序）。 */
    @GetMapping("/datasets/{id}/versions")
    public ApiResponse<List<DatasetVersionView>> versions(@PathVariable("id") Long datasetId) {
        return ApiResponse.ok(evaluationService.listVersions(datasetId));
    }

    /** 版本用例快照回看（与工作区用例同构 CaseView 列表）。 */
    @GetMapping("/datasets/{id}/versions/{versionId}/cases")
    public ApiResponse<List<CaseView>> versionCases(@PathVariable("id") Long datasetId,
                                                    @PathVariable Long versionId) {
        return ApiResponse.ok(evaluationService.listVersionCases(datasetId, versionId));
    }

    /** 删除版本（已被报告/任务引用 → 400）。 */
    @DeleteMapping("/datasets/{id}/versions/{versionId}")
    public ApiResponse<Void> deleteVersion(@PathVariable("id") Long datasetId,
                                           @PathVariable Long versionId,
                                           @RequestAttribute String username) {
        evaluationService.deleteVersion(datasetId, versionId, username);
        return ApiResponse.ok(null);
    }

    // ---------- 运行与报告 ----------

    /** 批量运行评测：openjudge 预置响应判分；报告落库 + audit_log 审计。 */
    @PostMapping("/run")
    public ApiResponse<ReportView> run(@Valid @RequestBody EvaluationRunRequest req,
                                       @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.run(req, username));
    }

    @GetMapping("/reports")
    public ApiResponse<List<ReportView>> reports() {
        return ApiResponse.ok(evaluationService.listReports());
    }

    @GetMapping("/reports/{id}")
    public ApiResponse<ReportView> report(@PathVariable Long id) {
        return ApiResponse.ok(evaluationService.getReport(id));
    }

    /** 报告对比：baseline 报告为基准，delta=current-baseline，metric 对齐缺项 null；
     * layer 可选按分层（basic/edge/real）过滤后重算逐指标均值，并回显 topDegradedSamples。 */
    @GetMapping("/reports/{id}/compare")
    public ApiResponse<ReportCompareView> compare(@PathVariable("id") Long currentId,
                                                  @RequestParam(required = false) Long baseline,
                                                  @RequestParam(required = false) String layer) {
        if (baseline == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "缺少 baseline 报告 id（compare 需指定基线报告）");
        }
        return ApiResponse.ok(evaluationService.compareReports(currentId, baseline, layer));
    }

    /** 评测目录：内置 17 指标静态元数据 + 启用的自定义评测器。 */
    @GetMapping("/catalog")
    public ApiResponse<List<MetricCatalogView>> catalog() {
        return ApiResponse.ok(evaluationService.catalog());
    }

    @DeleteMapping("/reports/{id}")
    public ApiResponse<Void> deleteReport(@PathVariable Long id, @RequestAttribute String username) {
        evaluationService.deleteReport(id);
        return ApiResponse.ok(null);
    }

    /** 报告逐轮转录（P2）：指定用例（caseSeq 必填）全量轮次，turn_no 升序。 */
    @GetMapping("/reports/{id}/transcript")
    public ApiResponse<List<TranscriptView>> reportTranscript(@PathVariable Long id,
                                                              @RequestParam(required = false) Integer caseSeq) {
        if (caseSeq == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 caseSeq 参数");
        }
        return ApiResponse.ok(evaluationService.listReportTranscript(id, caseSeq));
    }

    // ---------- 异步任务（H1） ----------

    /** 创建异步评测任务：202 Accepted + PENDING 任务视图，执行线程序后台跑（状态机轮询）。 */
    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<TaskView>> createTask(
            @Valid @RequestBody EvaluationRunRequest req,
            @RequestAttribute String username) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(evaluationService.createTask(req, username)));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskView>> tasks() {
        return ApiResponse.ok(evaluationService.listTasks());
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskDetailView> task(@PathVariable Long id) {
        return ApiResponse.ok(evaluationService.getTask(id));
    }

    /** 取消任务：PENDING/RUNNING 可取消，终态（COMPLETED/FAILED/CANCELED）→ 400。 */
    @PostMapping("/tasks/{id}/cancel")
    public ApiResponse<TaskView> cancelTask(@PathVariable Long id,
                                            @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.cancelTask(id, username));
    }

    /** 任务逐轮转录（P2）：经任务取报告；任务未产出报告（运行中/取消）返回空列表。 */
    @GetMapping("/tasks/{id}/transcript")
    public ApiResponse<List<TranscriptView>> taskTranscript(@PathVariable Long id,
                                                            @RequestParam(required = false) Integer caseSeq) {
        if (caseSeq == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 caseSeq 参数");
        }
        return ApiResponse.ok(evaluationService.listTaskTranscript(id, caseSeq));
    }

    // ---------- 人工复评与看板（P3/P4） ----------

    /** 人工复评提交/改判（同 report+caseSeq+metric 二次提交为覆盖更新）。 */
    @PostMapping("/reports/{id}/reviews")
    public ApiResponse<HumanReviewView> submitReview(@PathVariable Long id,
                                                     @Valid @RequestBody HumanReviewView.SaveRequest req,
                                                     @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.submitReview(id, req, username));
    }

    /** 复评列表（caseSeq/创建升序）；autoScore=true 时附逐样本自动分对齐。 */
    @GetMapping("/reports/{id}/reviews")
    public ApiResponse<List<HumanReviewView>> listReviews(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "false") boolean autoScore) {
        return ApiResponse.ok(evaluationService.listReviews(id, autoScore));
    }

    /** 人工 vs 自动校准对比（overall + 逐指标 topDeltas）。 */
    @GetMapping("/reports/{id}/reviews/calibration")
    public ApiResponse<JsonNode> calibration(@PathVariable Long id) {
        return ApiResponse.ok(evaluationService.calibration(id));
    }

    @DeleteMapping("/reviews/{id}")
    public ApiResponse<Void> deleteReview(@PathVariable Long id, @RequestAttribute String username) {
        evaluationService.deleteReview(id, username);
        return ApiResponse.ok(null);
    }

    /** 驾驶舱看板（A4）：最新报告分层 + 趋势 + 核心指标 series/latest/delta + 回归榜 + 成本延迟。 */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardView> dashboard(@RequestParam(required = false) Long datasetId,
                                                @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(evaluationService.dashboardReport(datasetId, limit));
    }

    /** 复现（T3）：以原报告的版本快照 + 评测器集重跑，新报告绑定 baseline_report_id。 */
    @PostMapping("/reports/{id}/rerun")
    public ApiResponse<ReportView> rerun(@PathVariable Long id, @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.rerunReport(id, username));
    }

    // ---------- 自定义评测器 ----------

    @GetMapping("/custom-evaluators")
    public ApiResponse<List<CustomEvaluatorView>> customEvaluators() {
        return ApiResponse.ok(evaluationService.listCustomEvaluators());
    }

    @PostMapping("/custom-evaluators")
    public ApiResponse<CustomEvaluatorView> createCustomEvaluator(
            @Valid @RequestBody CustomEvaluatorView.SaveRequest req,
            @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.createCustomEvaluator(req, username));
    }

    @PutMapping("/custom-evaluators/{id}")
    public ApiResponse<CustomEvaluatorView> updateCustomEvaluator(
            @PathVariable Long id, @Valid @RequestBody CustomEvaluatorView.SaveRequest req,
            @RequestAttribute String username) {
        return ApiResponse.ok(evaluationService.updateCustomEvaluator(id, req, username));
    }

    @DeleteMapping("/custom-evaluators/{id}")
    public ApiResponse<Void> deleteCustomEvaluator(@PathVariable Long id,
                                                   @RequestAttribute String username) {
        evaluationService.deleteCustomEvaluator(id);
        return ApiResponse.ok(null);
    }
}