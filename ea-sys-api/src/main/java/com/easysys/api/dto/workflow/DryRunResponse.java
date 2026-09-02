package com.easysys.api.dto.workflow;

import com.easysys.engine.service.AbstractDagExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 执行/干跑报告（动态聚合自 execution_node_state，非落库快照）。
 */
public record DryRunResponse(
        Long executionId,
        Long workflowId,
        Integer workflowVersion,
        String status,
        int totalMembers,
        boolean dryRun,
        long durationMs,
        String error,
        List<NodeOutcome> nodes,
        List<DeliveryLogView> deliveries) {

    public record NodeOutcome(
            String key,
            String nodeType,
            String nodeName,
            String status,
            int contacts,
            JsonNode output) {
    }

    public static DryRunResponse from(AbstractDagExecutor.ExecutionReport r, List<DeliveryLogView> deliveries) {
        List<NodeOutcome> outcomes = r.nodes().stream()
                .map(n -> new NodeOutcome(n.nodeKey(), n.nodeType(), n.nodeName(), n.status(),
                        n.contacts(), n.output()))
                .toList();
        return new DryRunResponse(r.executionId(), r.workflowId(), r.workflowVersion(), r.status(),
                r.totalMembers(), r.dryRun(), r.durationMs(), r.error(), outcomes, deliveries);
    }
}