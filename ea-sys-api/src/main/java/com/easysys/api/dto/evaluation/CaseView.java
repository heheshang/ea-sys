package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** 评测用例视图。 */
public record CaseView(
        Long id,
        Long datasetId,
        Integer seq,
        String question,
        String systemPrompt,
        JsonNode expectedOutput,
        JsonNode toolSchema,
        JsonNode expectedTool,
        Integer expectedSteps,
        JsonNode expectedPolicy,
        String providedResponse,
        Instant createdAt) {

    /** 新建/编辑用例请求。 */
    public record SaveRequest(
            Integer seq,
            String question,
            String systemPrompt,
            JsonNode expectedOutput,
            JsonNode toolSchema,
            JsonNode expectedTool,
            Integer expectedSteps,
            JsonNode expectedPolicy,
            String providedResponse) {
    }
}