package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单次智能体调用的审计载荷（调用方落 audit_log）。
 * status ∈ SUCCESS / FALLBACK / ERROR；reason 记录降级原因
 * （schema_invalid / low_confidence / provider_error:* / fallback_invalid）。
 */
public record AgentCall(
        AgentType agentType,
        String action,
        String status,
        String reason,
        JsonNode inputSummary,
        JsonNode output,
        String strategyVersion,
        Double confidence,
        String model,
        Integer tokens,
        long durationMs) {
}