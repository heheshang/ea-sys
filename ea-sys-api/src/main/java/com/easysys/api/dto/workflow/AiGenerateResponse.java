package com.easysys.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * AI 创建响应：DAG 草稿 + 工具调用时间线 + 计划摘要 + 人群建议。
 * workflowDraft 可直接提交现有保存接口（POST /api/workflows）——人工审核后再落库。
 */
public record AiGenerateResponse(
        SaveWorkflowRequest workflowDraft,
        List<AiToolCallView> toolCalls,
        String planSummary,
        JsonNode audienceHint) {
}