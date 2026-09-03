package com.easysys.api.dto.retention;

import java.util.List;

/**
 * 工作流效果：每个工作流最近一次执行的触达规模 + 留存（触达后 N 天仍有活跃事件的人占比）。
 */
public record WorkflowEffectView(
        List<WorkflowEffectItem> workflows) {

    public record WorkflowEffectItem(
            long workflowId,
            String workflowName,
            long reached,
            long retained,
            double retentionRate) {
    }
}