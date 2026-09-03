package com.easysys.api.dto.cockpit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 图谱登记项视图：内置目录（source=builtin，id=null）与用户登记行（source=user）统一形状；
 * 同 (module, entry_key) 用户行覆盖内置项。
 */
public record AgentGraphEntryView(
        Long id,
        String module,
        String entryKey,
        String name,
        String description,
        JsonNode payload,
        String status,
        String version,
        String source,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 新建/编辑图谱登记请求（接口内联）。 */
    public record SaveRequest(
            String module,
            String entryKey,
            String name,
            String description,
            JsonNode payload,
            String status,
            String version) {
    }
}