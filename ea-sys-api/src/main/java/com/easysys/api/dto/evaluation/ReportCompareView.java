package com.easysys.api.dto.evaluation;

import java.time.Instant;
import java.util.List;

/** 报告对比视图（H4）：以 baseline 为基准，逐指标对齐 current 的均值差与方向（红绿由前端判）。 */
public record ReportCompareView(
        ReportRef baseline,
        ReportRef current,
        List<CompareMetric> metrics,
        /** 分层过滤参数回显（basic/edge/real；null = 未按层过滤）。 */
        String layer,
        /** 逐样本降级前 5（两份报告均有关联任务逐样本时按 |delta| 降序；无逐样本数据 → 空列表）。 */
        List<TopDegradedSample> topDegradedSamples) {

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

    /** 逐样本降级：同 caseSeq 当前/基线分数对齐；缺项为 null，delta = auto - baseline。 */
    public record TopDegradedSample(
            Long caseSeq,
            Double auto,
            Double baseline,
            Double delta) {
    }
}