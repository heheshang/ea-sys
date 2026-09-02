package com.easysys.api.dto.workflow;

import java.time.Instant;
import java.util.List;

/**
 * 工作流 + 当前版本画布（节点/边）。status: draft / published / archived。
 */
public record WorkflowView(
        Long id,
        String name,
        String description,
        String status,
        Integer version,
        Instant publishedAt,
        String createdBy,
        Instant createdAt,
        List<WorkflowNodeSpec> nodes,
        List<WorkflowEdgeSpec> edges) {
}