package com.easysys.api.controller;

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
import com.easysys.api.service.EvaluationService;
import com.easysys.common.web.ApiResponse;
import com.easysys.common.web.BizException;
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

    /** 报告对比：baseline 报告为基准，delta=current-baseline，metric 对齐缺项 null。 */
    @GetMapping("/reports/{id}/compare")
    public ApiResponse<ReportCompareView> compare(@PathVariable("id") Long currentId,
                                                  @RequestParam(required = false) Long baseline) {
        if (baseline == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "缺少 baseline 报告 id（compare 需指定基线报告）");
        }
        return ApiResponse.ok(evaluationService.compareReports(currentId, baseline));
    }

    /** 评测目录：内置 15 指标静态元数据 + 启用的自定义评测器。 */
    @GetMapping("/catalog")
    public ApiResponse<List<MetricCatalogView>> catalog() {
        return ApiResponse.ok(evaluationService.catalog());
    }

    @DeleteMapping("/reports/{id}")
    public ApiResponse<Void> deleteReport(@PathVariable Long id, @RequestAttribute String username) {
        evaluationService.deleteReport(id);
        return ApiResponse.ok(null);
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