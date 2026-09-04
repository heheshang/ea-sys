package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 评测逐轮转录视图：单轮消息（USER 提问 / ASSISTANT 回复 / TOOL 工具调用或结果）。
 *
 * <p>text 与 thinking 仅 USER/ASSISTANT 消息有值；toolUse/toolResult 仅 TOOL 消息有值
 * （同一 TOOL 消息可同时含调用与结果 → 两条记录）。createdAt 为转录落库时间。</p>
 */
public record TranscriptView(Integer turnNo, String role, String text, String thinking,
                             JsonNode toolUse, JsonNode toolResult, Instant createdAt) {
}