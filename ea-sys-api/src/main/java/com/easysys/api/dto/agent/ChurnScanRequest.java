package com.easysys.api.dto.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 流失风险批量扫描请求：对快照成员按「N 天未活跃 = HIGH」规则评估。
 * inactiveDays 为阈值天数（默认 30，docs/04-agent-design.md §5）。
 */
public record ChurnScanRequest(
        @NotNull Long audienceSnapshotId,
        @Min(1) Integer inactiveDays) {
}