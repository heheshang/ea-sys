package com.easysys.api.dto.retention;

import java.time.Instant;

/**
 * 区间留存：前一窗口活跃人群（cohort）中，本窗口仍活跃的比例。
 * 定义：窗口 N 天，cohort = 窗口 [now-2N, now-N) 内有行为事件的人；留存 = cohort 中在 [now-N, now) 仍有事件的比例。
 */
public record IntervalRetentionView(
        int days,
        long cohort,
        long retained,
        double rate,
        Instant priorWindowStart,
        Instant priorWindowEnd,
        Instant currentWindowStart,
        Instant currentWindowEnd) {
}