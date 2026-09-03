package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** 自定义评测器视图（metric = custom_{id}，评测运行时按此引用）。 */
public record CustomEvaluatorView(
        Long id,
        String metric,
        String name,
        String category,
        String description,
        String ruleType,
        JsonNode params,
        String judgePrompt,
        String status,
        String createdBy,
        Instant createdAt) {

    /** 新建/编辑自定义评测器请求。 */
    public record SaveRequest(
            String name,
            String category,
            String description,
            String ruleType,
            JsonNode params,
            String judgePrompt,
            String status) {
    }
}