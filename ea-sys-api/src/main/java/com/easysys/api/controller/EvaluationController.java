package com.easysys.api.controller;

import com.easysys.api.dto.evaluation.CaseView;
import com.easysys.api.dto.evaluation.DatasetView;
import com.easysys.api.dto.evaluation.EvaluationRunRequest;
import com.easysys.api.dto.evaluation.ReportView;
import com.easysys.api.service.EvaluationService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测中心：数据集 + 用例管理，批量运行（AgentPolicy 确定性评测模型 + 审计），报告回看。
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

    @DeleteMapping("/reports/{id}")
    public ApiResponse<Void> deleteReport(@PathVariable Long id, @RequestAttribute String username) {
        evaluationService.deleteReport(id);
        return ApiResponse.ok(null);
    }
}