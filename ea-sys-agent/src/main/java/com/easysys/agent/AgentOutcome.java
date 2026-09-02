package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 智能体执行结果：最终输出（主提供方或 fallback）+ 审计载荷。
 * status 与 audit.status 一致；调用方只消费 output，审计由调用方持久化。
 */
public record AgentOutcome(String status, String reason, JsonNode output, AgentCall audit) {
}