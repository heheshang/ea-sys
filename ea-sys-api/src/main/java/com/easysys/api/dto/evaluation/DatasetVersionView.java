package com.easysys.api.dto.evaluation;

import java.time.Instant;

/** 数据集版本视图（caseCount 为快照用例数）。 */
public record DatasetVersionView(
        Long id,
        Long datasetId,
        Integer versionNo,
        String status,
        int caseCount,
        Instant publishedAt,
        String createdBy) {
}