package com.easysys.api.dto.audience;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人群创建/更新请求。rule 为结构化 DSL（见 docs/03-workflow-engine.md §3）。
 */
public record AudienceRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull JsonNode rule) {
}