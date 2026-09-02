package com.easysys.api.dto.plan;

import java.util.List;

/**
 * 结构化计划文档：运营计划文件（xlsx / csv）解析产物。
 * 固定模板映射：Sheet1 计划概览 → overview；Sheet2 触达计划 → routes；
 * Sheet3 人群规则 → audienceRules（可选）；Sheet4 文案要求 → copyNotes（可选）。
 */
public record PlanDocument(
        PlanOverview overview,
        List<PlanRouteRow> routes,
        List<PlanAudienceRule> audienceRules,
        List<PlanCopyNote> copyNotes) {

    /** Sheet1 计划概览。triggerType ∈ TIMED / EVENT / MANUAL。 */
    public record PlanOverview(
            String planName,
            String audienceTarget,
            String triggerType,
            String triggerTime,
            String eventName,
            String timezone,
            String budgetCap) {
    }

    /** Sheet2 触达计划核心路由行。timing 形如 "D+0 09:00" / "D+1" / 固定时刻。 */
    public record PlanRouteRow(
            String layer,
            String channel,
            Integer sequence,
            String timing,
            String templateName,
            Integer frequencyLimit,
            String remark) {
    }

    /** Sheet3 人群规则行（语义维度，首版仅记录）。 */
    public record PlanAudienceRule(String op, String field, String operator, String value) {
    }

    /** Sheet4 文案要求行（语义维度，首版仅记录）。 */
    public record PlanCopyNote(String channel, String template, String requirement) {
    }
}