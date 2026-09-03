package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/** 评测报告视图（指标均值/发现/汇总均以 JSON 原文回读）。 */
public record ReportView(
        Long id,
        Long datasetId,
        String name,
        int totalCases,
        int testedCases,
        JsonNode metrics,
        JsonNode findings,
        JsonNode summary,
        BigDecimal confidence,
        String model,
        String mode,
        Integer judgeRounds,
        String traceId,
        String createdBy,
        Instant createdAt) {
}