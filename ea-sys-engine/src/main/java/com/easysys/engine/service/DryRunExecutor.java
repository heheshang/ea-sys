package com.easysys.engine.service;

import com.easysys.engine.EngineException;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.entity.WorkflowEdge;
import com.easysys.engine.entity.WorkflowNode;
import com.easysys.engine.mapper.ExecutionMapper;
import com.easysys.engine.mapper.ExecutionNodeStateMapper;
import com.easysys.engine.rule.ConditionCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * DAG 干跑执行器：复用 {@link AbstractDagExecutor} 的拓扑推进与状态落库，
 * ACTION 节点仅按 config 估算接触人数与成本，不产生真实触达。
 * 记录/报告类型（MemberContext/NodeOutcome/ExecutionReport）同 AbstractDagExecutor，
 * 与真实执行器语义一致，报告可对比。
 */
@Service
public class DryRunExecutor extends AbstractDagExecutor {

    public DryRunExecutor(ExecutionMapper executionMapper, ExecutionNodeStateMapper stateMapper,
                          ConditionCompiler compiler) {
        super(executionMapper, stateMapper, compiler);
    }

    /** 干跑入口：dryRun=true，执行共享 DAG 推进。 */
    public ExecutionReport execute(Workflow workflow, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                   Long audienceSnapshotId, List<MemberContext> members) {
        return execute(workflow, nodes, edges, audienceSnapshotId, members, true);
    }

    @Override
    protected void handleAction(Long executionId, WorkflowNode node, LinkedHashSet<Long> here,
                                Map<Long, MemberContext> byId, ObjectNode output) {
        JsonNode cfg = parseConfig(node);
        String channel = cfg.path("channel").asText(null);
        Long templateId = cfg.path("templateId").isNumber() ? cfg.path("templateId").asLong() : null;
        if (channel == null || templateId == null) {
            throw new EngineException("ACTION 节点 " + node.getNodeKey() + " 缺少 channel/templateId 配置");
        }
        int contacts = here == null ? 0 : here.size();
        output.put("channel", channel);
        output.put("templateId", templateId);
        output.put("contacts", contacts);
        JsonNode unitCost = cfg.get("unitCost");
        if (unitCost != null && unitCost.isNumber()) {
            BigDecimal cost = unitCost.decimalValue().multiply(BigDecimal.valueOf(contacts));
            output.put("estimatedCost", cost);
        }
    }
}