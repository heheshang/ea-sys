package com.easysys.api.dto.cockpit;

import java.time.Instant;
import java.util.List;

/**
 * 驾驶舱洞察视图：健康分 + 分级洞察列表（缓存 300s，force=1 绕过）。
 */
public record CockpitInsightView(
        Instant generatedAt,
        int overallHealth,
        List<Insight> insights) {

    public record Insight(
            String level,
            String dimension,
            String detail,
            String suggestion) {
    }
}