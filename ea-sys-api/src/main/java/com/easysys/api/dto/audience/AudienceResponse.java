package com.easysys.api.dto.audience;

import java.time.Instant;

/**
 * 人群响应。详情与列表共用；rule 列表也返回（量小）。
 * latestSnapshot 为最近一次圈选摘要（列表 LATERAL 带出，详情单独查）。
 */
public record AudienceResponse(
        Long id,
        String name,
        String rule,
        Integer version,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        LatestSnapshot latestSnapshot) {

    public record LatestSnapshot(Long id, String status, Integer memberCount, Instant executedAt) {
    }
}