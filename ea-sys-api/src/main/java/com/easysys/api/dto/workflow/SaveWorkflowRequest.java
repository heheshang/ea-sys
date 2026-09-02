package com.easysys.api.dto.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 画布保存请求。版本化语义：DRAFT 覆盖当前版本行；PUBLISHED 后保存生成 version+1 新行。
 */
public record SaveWorkflowRequest(
        @NotBlank @Size(max = 128) String name,
        String description,
        @NotNull @Valid List<WorkflowNodeSpec> nodes,
        @NotNull @Valid List<WorkflowEdgeSpec> edges) {
}