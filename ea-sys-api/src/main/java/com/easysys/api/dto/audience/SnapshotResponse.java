package com.easysys.api.dto.audience;

import java.time.Instant;

public record SnapshotResponse(
        Long id,
        Long audienceId,
        Instant executedAt,
        Integer memberCount,
        String status,
        Integer filterVersion) {

    public static SnapshotResponse of(com.easysys.api.entity.AudienceSnapshot s) {
        return new SnapshotResponse(s.getId(), s.getAudienceId(), s.getExecutedAt(),
                s.getMemberCount(), s.getStatus(), s.getFilterVersion());
    }
}