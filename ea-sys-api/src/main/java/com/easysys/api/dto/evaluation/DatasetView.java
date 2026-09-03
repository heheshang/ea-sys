package com.easysys.api.dto.evaluation;

import java.time.Instant;

/** 评测数据集视图。 */
public record DatasetView(
        Long id,
        String name,
        String description,
        String scope,
        String mode,
        String status,
        int caseCount,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 新建/编辑数据集请求。 */
    public record SaveRequest(
            String name,
            String description,
            String scope,
            String mode,
            String status) {
    }
}