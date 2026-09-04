package com.easysys.api.dto.evaluation;

import java.time.Instant;
import java.util.List;

/** 报告对比视图（H4）：以 baseline 为基准，逐指标对齐 current 的均值差与方向（红绿由前端判）。 */
public record ReportCompareView(
        ReportRef baseline,
        ReportRef current,
        List<CompareMetric> metrics) {

    /** 报告引用信息。 */
    public record ReportRef(
            Long id,
            Instant createdAt,
            String name) {
    }

    /** 单指标对比行：缺项为 null，delta = current - baseline。 */
    public record CompareMetric(
            String metric,
            String category,
            Double current,
            Double baseline,
            Double delta,
            String direction) {
    }
}