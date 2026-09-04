package com.easysys.api.dto.evaluation;

/** 评测指标目录项（H6）：指标元数据（direction 供前端红绿渲染）。 */
public record MetricCatalogView(
        String metric,
        String category,
        boolean higherIsBetter,
        double defaultThreshold) {
}