package com.easysys.api.dto.agent;

/**
 * 流失风险扫描结果：聚合统计 + 回写属性数。
 */
public record ChurnScanView(
        long audienceSnapshotId,
        int thresholdDays,
        int scanned,
        int high,
        int medium,
        int low,
        int updatedAttributes) {
}