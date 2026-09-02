package com.easysys.api.controller;

import com.easysys.api.dto.workflow.DryRunRequest;
import com.easysys.api.dto.workflow.DryRunResponse;
import com.easysys.api.dto.workflow.SaveWorkflowRequest;
import com.easysys.api.dto.workflow.ValidationResponse;
import com.easysys.api.dto.workflow.WorkflowSnapshotListView;
import com.easysys.api.dto.workflow.WorkflowSummaryView;
import com.easysys.api.dto.workflow.WorkflowVersionView;
import com.easysys.api.dto.workflow.WorkflowView;
import com.easysys.api.service.WorkflowService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画布工作流：保存 → 校验 → 发布 → 干跑 → 报告。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** 工作流列表：每业务 id 族最新可用行。 */
    @GetMapping
    public ApiResponse<List<WorkflowSummaryView>> list() {
        return ApiResponse.ok(workflowService.list());
    }

    /** 新建画布（v1 DRAFT）。 */
    @PostMapping
    public ApiResponse<WorkflowView> create(@Valid @RequestBody SaveWorkflowRequest req,
                                            @RequestAttribute String username) {
        return ApiResponse.ok(workflowService.save(null, req, username));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkflowView> get(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.get(id));
    }

    /** 更新画布：DRAFT 覆盖当前行；已发布生成 version+1 新行。 */
    @PutMapping("/{id}")
    public ApiResponse<WorkflowView> update(@PathVariable Long id,
                                            @Valid @RequestBody SaveWorkflowRequest req,
                                            @RequestAttribute String username) {
        return ApiResponse.ok(workflowService.save(id, req, username));
    }

    /** 校验当前版本画布结构；errors 为空即可发布。 */
    @PostMapping("/{id}/validate")
    public ApiResponse<ValidationResponse> validate(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.validate(id));
    }

    /** 发布记录：某业务 id 全部版本行（含发布人/发布时间）。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<WorkflowVersionView>> versions(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.versions(id));
    }

    /** 快照列表：发布快照（版本行）+ 干跑快照（dry-run 执行记录）。 */
    @GetMapping("/{id}/snapshots")
    public ApiResponse<WorkflowSnapshotListView> snapshots(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.snapshots(id));
    }

    /** 发布当前草稿版本（旧发布行归档）。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<WorkflowView> publish(@PathVariable Long id,
                                             @RequestAttribute String username) {
        return ApiResponse.ok(workflowService.publish(id, username));
    }

    /** 干跑：对已发布版本 + 快照成员模拟执行，返回报告。 */
    @PostMapping("/{id}/dry-run")
    public ApiResponse<DryRunResponse> dryRun(@PathVariable Long id,
                                              @Valid @RequestBody DryRunRequest req) {
        return ApiResponse.ok(workflowService.dryRun(id, req));
    }

    /** 真实触达执行：ACTION 节点按模板经频道下发，幂等+频率治理，返回报告。 */
    @PostMapping("/{id}/execute")
    public ApiResponse<DryRunResponse> execute(@PathVariable Long id,
                                               @Valid @RequestBody DryRunRequest req) {
        return ApiResponse.ok(workflowService.execute(id, req));
    }

    /** 按执行实例查询干跑报告（执行后重查）。 */
    @GetMapping("/executions/{executionId}/report")
    public ApiResponse<DryRunResponse> report(@PathVariable Long executionId) {
        return ApiResponse.ok(workflowService.report(executionId));
    }
}