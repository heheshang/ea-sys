package com.easysys.api.dto.retention;

/**
 * 转化漏斗：圈选人数 → 进入执行人数 → 触达人数（按工作流或租户聚合）。
 */
public record FunnelView(
        Long workflowId,
        long seeded,
        long executed,
        long reached,
        double seededToExecutedRate,
        double executedToReachedRate) {
}