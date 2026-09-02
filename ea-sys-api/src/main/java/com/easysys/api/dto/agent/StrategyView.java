package com.easysys.api.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/** 分层策略视图（完整策略文档 + 发布状态）。 */
public record StrategyView(
        Long id,
        String name,
        JsonNode dimensions,
        JsonNode routeOrder,
        JsonNode strategy,
        String source,
        String status,
        String strategyVersion,
        BigDecimal confidence,
        String createdBy,
        Instant createdAt,
        Instant publishedAt) {
}