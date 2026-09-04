package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/** 评测任务视图（H1）：状态机 + 逐样本进度 + 逐样本明细。 */
public record TaskView(
        Long id,
        String name,
        Long datasetId,
        String status,
        int totalCases,
        int testedCases,
        BigDecimal progressPct,
        String errorMessage,
        Long reportId,
        JsonNode sampleResults,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}