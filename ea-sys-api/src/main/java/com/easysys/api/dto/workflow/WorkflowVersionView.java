package com.easysys.api.dto.workflow;

import java.time.Instant;

/**
 * 工作流版本/发布记录行（GET /api/workflows/{id}/versions）。
 * 每个业务 id（ref_id）按 version 全量返回；published/archived 行含发布人与发布时间。
 */
public record WorkflowVersionView(
        Integer version,
        Long refId,
        String name,
        String status,
        String publishedBy,
        Instant publishedAt,
        String createdBy,
        Instant createdAt) {
}