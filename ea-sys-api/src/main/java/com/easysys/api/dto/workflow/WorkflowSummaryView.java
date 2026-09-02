package com.easysys.api.dto.workflow;

import java.time.Instant;

/**
 * 工作流列表行（每业务 id 族最新可用行，不含画布；画布走 GET /api/workflows/{id}）。
 */
public record WorkflowSummaryView(
        Long id,
        String name,
        String description,
        String status,
        Integer version,
        Instant publishedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}