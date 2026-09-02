package com.easysys.api.dto.workflow;

import java.time.Instant;

/**
 * 执行历史行（GET /api/workflows/executions）：干跑/真实执行记录，附工作流名与人群快照信息。
 */
public record ExecutionSummaryView(
        Long executionId,
        Long workflowId,
        String workflowName,
        Integer workflowVersion,
        String triggerType,
        Boolean dryRun,
        String status,
        Long audienceSnapshotId,
        String audienceName,
        Integer memberCount,
        Instant startedAt,
        Instant finishedAt) {
}