package com.easysys.api.dto.cockpit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * LLM 调用追踪行（audit_log 最近 N 条）。
 */
public record LlmTraceView(
        Long id,
        String agentType,
        String action,
        String status,
        String reason,
        String model,
        Integer tokens,
        Long durationMs,
        BigDecimal cost,
        BigDecimal confidence,
        Boolean schemaValid,
        String operator,
        Instant createdAt) {
}