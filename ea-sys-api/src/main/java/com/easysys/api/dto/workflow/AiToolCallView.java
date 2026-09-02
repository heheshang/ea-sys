package com.easysys.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI 生成过程中的一次工具调用（前端时间线展示）。
 * arguments 为工具入参摘要，result 为结果摘要（大字段已截断）。
 */
public record AiToolCallView(
        String name,
        JsonNode arguments,
        JsonNode result,
        String status,
        Long durationMs) {
}