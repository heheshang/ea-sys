package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 评测驾驶舱聚合视图（A4）：分层洞察 + 报告趋势（最近 N 条）+ 核心指标 series/latest/delta +
 * 回归榜 + 成本与延迟。全部派生自报告 summary/execution JSONB；无报告时键为 null/空数组。
 */
public record DashboardView(
        JsonNode layering,
        List<JsonNode> trend,
        JsonNode metrics,
        JsonNode regressions,
        JsonNode costLatency) {
}