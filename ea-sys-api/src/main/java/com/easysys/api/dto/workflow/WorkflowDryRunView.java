package com.easysys.api.dto.workflow;

import java.time.Instant;

/**
 * 干跑快照行：一次干跑执行记录（execution.dry_run=true 冻结的画布版本 + 人群快照）。
 */
public record WorkflowDryRunView(
        Long executionId,
        Integer workflowVersion,
        Long audienceSnapshotId,
        String audienceName,
        Integer memberCount,
        String status,
        Instant startedAt,
        Instant finishedAt) {
}