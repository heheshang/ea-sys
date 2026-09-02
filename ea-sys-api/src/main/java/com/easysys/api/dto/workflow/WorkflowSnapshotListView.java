package com.easysys.api.dto.workflow;

import java.util.List;

/**
 * 工作流快照列表（GET /api/workflows/{id}/snapshots）：
 * 发布快照（版本行）+ 干跑快照（execution dry_run=true 记录）。
 */
public record WorkflowSnapshotListView(
        List<WorkflowVersionView> publishSnapshots,
        List<WorkflowDryRunView> dryRunSnapshots) {
}