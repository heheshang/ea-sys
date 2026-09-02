package com.easysys.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 画布节点。type ∈ TRIGGER / CONDITION / DELAY / ACTION / UPDATE / END（split/merge M3）。
 * config/position 为宁缺勿滥的 JSON 对象：ACTION 记 {channel, templateId, unitCost?}，
 * DELAY 记 {minutes}，CONDITION 条件挂在出边上。
 */
public record WorkflowNodeSpec(
        @NotBlank @Size(max = 64) String key,
        @NotBlank @Size(max = 16) String type,
        @Size(max = 128) String name,
        JsonNode config,
        JsonNode position) {
}