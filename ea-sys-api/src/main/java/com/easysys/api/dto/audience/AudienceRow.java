package com.easysys.api.dto.audience;

import java.time.Instant;

/**
 * 人群分页查询扁平行（MyBatis resultType record 不支持点号嵌套映射；
 * 最近快照摘要以 flat 列带回，由 Service 组装 AudienceResponse）。
 */
public record AudienceRow(Long id, String name, String rule, Integer version, String status, String createdBy,
                          Instant createdAt, Instant updatedAt,
                          Long latestSnapshotId, String latestSnapshotStatus,
                          Integer latestSnapshotMemberCount, Instant latestSnapshotExecutedAt) {
}