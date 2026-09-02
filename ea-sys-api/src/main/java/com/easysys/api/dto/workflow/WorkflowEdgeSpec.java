package com.easysys.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 画布边。condition 为条件 DSL（见 docs/03-workflow-engine.md §3）结构化 JSON：
 * CONDITION 出边带条件，无条件边作为兜底（else）分支。
 */
public record WorkflowEdgeSpec(
        @NotBlank @Size(max = 64) String source,
        @NotBlank @Size(max = 64) String target,
        JsonNode condition) {
}