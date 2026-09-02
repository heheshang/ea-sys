package com.easysys.api.dto.plan;

import java.util.List;

/**
 * 计划校验报告视图（API 响应与 validation_report.report JSON 同构）。
 * decision ∈ PASSED / WARNINGS / BLOCKED；summary 分级计数。
 */
public record PlanValidationView(
        Long id,
        Long workflowId,
        String planName,
        String fileType,
        String fileName,
        String decision,
        String planSummary,
        List<Dimension> dimensions,
        Summary summary,
        String createdAt,
        String createdBy) {

    /** 单维度比对结果。level ∈ PASSED / WARNINGS / BLOCKED。 */
    public record Dimension(String name, String level, String plan, String workflow,
                            String detail, String suggestion) {
    }

    /** 汇总计数：conflicts = BLOCKED 数，warnings = WARNINGS 数，passed = 其余维度数。 */
    public record Summary(int conflicts, int warnings, int passed) {
    }
}