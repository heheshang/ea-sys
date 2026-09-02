package com.easysys.engine.service;

import com.easysys.common.tenant.TenantContext;
import com.easysys.engine.EngineException;
import com.easysys.engine.entity.Execution;
import com.easysys.engine.entity.ExecutionNodeState;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.entity.WorkflowEdge;
import com.easysys.engine.entity.WorkflowNode;
import com.easysys.engine.mapper.ExecutionMapper;
import com.easysys.engine.mapper.ExecutionNodeStateMapper;
import com.easysys.engine.model.ExecutionStatus;
import com.easysys.engine.model.NodeStateStatus;
import com.easysys.engine.model.NodeType;
import com.easysys.engine.model.TriggerType;
import com.easysys.engine.rule.ConditionCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * DAG 干跑执行器：拓扑序推进状态机，CONDITION 按出边 DSL 分流，ACTION/UPDATE 累加预计人数。
 * 落库 execution + execution_node_state（一行/节点，自包含 node_type/node_name/output），
 * 报告由本执行结果动态聚合（无独立 report 表）。
 *
 * 语义：
 * - 批量模式：成员集合 = 快照画像快照（event 上下文为空 map，事件触发模式留待 M3）
 * - CONDITION：按出边顺序首个条件命中 → 路由；无条件出边为兜底；全不命中 → 丢弃
 * - 节点异常 → 该节点 FAILED、执行 FAILED 并提交（dry-run 报告可见失败），后续节点不再执行
 */
@Service
public class DryRunExecutor {

    private final ExecutionMapper executionMapper;
    private final ExecutionNodeStateMapper stateMapper;
    private final ConditionCompiler compiler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DryRunExecutor(ExecutionMapper executionMapper, ExecutionNodeStateMapper stateMapper,
                          ConditionCompiler compiler) {
        this.executionMapper = executionMapper;
        this.stateMapper = stateMapper;
        this.compiler = compiler;
    }

    /** 成员画像上下文（event 触发模式 M3 使用，批量干跑为空） */
    public record MemberContext(Long contactId,
                                Map<String, Object> contact,
                                Map<String, Object> history,
                                Map<String, Object> event) {
    }

    /** 单节点执行结果（node_state 行 + 报告条目同构） */
    public record NodeOutcome(String nodeKey, String nodeType, String nodeName, String status,
                              int contacts, JsonNode output) {
    }

    /** 干跑报告（动态聚合，无独立表） */
    public record ExecutionReport(Long executionId, Long workflowId, Integer workflowVersion, String status,
                                  int totalMembers, boolean dryRun, long durationMs, String error,
                                  List<NodeOutcome> nodes) {
    }

    @Transactional
    public ExecutionReport execute(Workflow workflow, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                   Long audienceSnapshotId, List<MemberContext> members) {
        Instant startedAt = Instant.now();
        Long tenantId = TenantContext.require();
        Long workflowId = workflow.getRefId() != null ? workflow.getRefId() : workflow.getId();
        Integer version = workflow.getVersion();

        Execution execution = new Execution();
        execution.setTenantId(tenantId);
        execution.setWorkflowId(workflowId);
        execution.setWorkflowVersion(version);
        execution.setTriggerType(TriggerType.MANUAL.name());
        execution.setAudienceSnapshotId(audienceSnapshotId);
        execution.setDryRun(true);
        execution.setStatus(ExecutionStatus.RUNNING.name());
        execution.setStartedAt(startedAt);
        execution.setCreatedAt(startedAt);
        execution.setUpdatedAt(startedAt);
        executionMapper.insert(execution);
        Long executionId = execution.getId();

        String error = null;
        try {
            runNodes(executionId, tenantId, nodes, edges, members);
            Execution update = new Execution();
            update.setId(executionId);
            update.setStatus(ExecutionStatus.SUCCEEDED.name());
            update.setFinishedAt(Instant.now());
            update.setUpdatedAt(Instant.now());
            executionMapper.updateById(update);
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Execution update = new Execution();
            update.setId(executionId);
            update.setStatus(ExecutionStatus.FAILED.name());
            update.setFinishedAt(Instant.now());
            update.setUpdatedAt(Instant.now());
            executionMapper.updateById(update);
        }
        Execution persisted = executionMapper.selectById(executionId);
        return buildReport(persisted, workflowId, version, members == null ? 0 : members.size(), error);
    }

    /** 查询执行报告（报告动态聚合，由 execution + node_state 重现执行结果）。 */
    public ExecutionReport report(Long executionId) {
        Execution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            return null;
        }
        int totalMembers = 0;
        for (ExecutionNodeState s : statesOf(executionId)) {
            if ("TRIGGER".equals(s.getNodeType()) && s.getOutput() != null) {
                try {
                    JsonNode out = objectMapper.readTree(s.getOutput());
                    JsonNode t = out.get("totalMembers");
                    if (t != null && t.isNumber()) {
                        totalMembers = t.asInt();
                        break;
                    }
                } catch (Exception ignored) {
                    // output 由引擎写入，解析失败仅影响人数展示
                }
            }
        }
        return buildReport(execution, execution.getWorkflowId(), execution.getWorkflowVersion(), totalMembers, null);
    }

    private List<ExecutionNodeState> statesOf(Long executionId) {
        return stateMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExecutionNodeState>()
                        .eq(ExecutionNodeState::getExecutionId, executionId)
                        .orderByAsc(ExecutionNodeState::getId));
    }

    private void runNodes(Long executionId, Long tenantId, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                          List<MemberContext> members) {
        long total = members == null ? 0 : members.size();
        if (members == null) {
            members = List.of();
        }
        // 由全体成员初始到达 TRIGGER（抽样 M3 接入）
        LinkedHashSet<Long> allIds = new LinkedHashSet<>();
        for (MemberContext m : members) {
            allIds.add(m.contactId);
        }

        // 拓扑序（Kahn）
        List<String> order = topoOrder(nodes, edges);
        Map<String, WorkflowNode> nodeByKey = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) {
            nodeByKey.put(n.getNodeKey(), n);
        }
        Map<String, List<WorkflowEdge>> outByKey = new LinkedHashMap<>();
        for (WorkflowEdge e : edges) {
            outByKey.computeIfAbsent(e.getSourceKey(), k -> new ArrayList<>()).add(e);
        }

        // 传播：nodeKey -> 到达成员
        Map<String, LinkedHashSet<Long>> arriving = new HashMap<>();
        String trigger = order.get(0); // 拓扑序首节点即 TRIGGER（入度 0 唯一）
        arriving.put(trigger, allIds);

        // 单次执行的条件编译缓存（edge 维度），执行期不再重复编译
        Map<String, ConditionCompiler.CompiledCondition> conditionCache = new HashMap<>();

        for (String key : order) {
            Instant inAt = Instant.now();
            try {
                LinkedHashSet<Long> here = arriving.get(key);
                WorkflowNode node = nodeByKey.get(key);
                NodeType type = NodeType.valueOf(node.getType());
                int contacts = here == null ? 0 : here.size();

                ObjectNode output = objectMapper.createObjectNode();
                List<WorkflowEdge> outs = outByKey.getOrDefault(key, List.of());
                if (type == NodeType.CONDITION) {
                    splitConditional(here, outs, members, conditionCache, output, arriving);
                } else if (type == NodeType.ACTION) {
                    actionOutput(node, contacts, output);
                    passThrough(outs, here, arriving);
                } else if (type == NodeType.UPDATE) {
                    output.put("contacts", contacts);
                    passThrough(outs, here, arriving);
                } else if (type == NodeType.DELAY) {
                    output.put("delayMinutes", delayMinutes(node));
                    passThrough(outs, here, arriving);
                } else if (type == NodeType.END) {
                    output.put("contacts", contacts);
                } else if (type == NodeType.TRIGGER) {
                    output.put("totalMembers", contacts);
                    passThrough(outs, here, arriving);
                } else {
                    throw new EngineException("不支持的节点类型: " + node.getType());
                }

                ExecutionNodeState state = new ExecutionNodeState();
                state.setTenantId(tenantId);
                state.setExecutionId(executionId);
                state.setNodeKey(key);
                state.setNodeType(node.getType());
                state.setNodeName(node.getName());
                state.setStatus(NodeStateStatus.DONE.name());
                state.setAttempt(0);
                state.setInAt(inAt);
                state.setOutAt(Instant.now());
                state.setOutput(output.toString());
                state.setUpdatedAt(Instant.now());
                stateMapper.insert(state);
            } catch (Exception e) {
                // 失败节点落库 FAILED + error（报告可见病根），向上抛出置整个执行 FAILED
                ExecutionNodeState failed = new ExecutionNodeState();
                failed.setTenantId(tenantId);
                failed.setExecutionId(executionId);
                failed.setNodeKey(key);
                WorkflowNode node = nodeByKey.get(key);
                failed.setNodeType(node == null ? null : node.getType());
                failed.setNodeName(node == null ? null : node.getName());
                failed.setStatus(NodeStateStatus.FAILED.name());
                failed.setAttempt(0);
                failed.setInAt(inAt);
                failed.setOutAt(Instant.now());
                ObjectNode err = objectMapper.createObjectNode();
                err.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed.setOutput(err.toString());
                failed.setUpdatedAt(Instant.now());
                stateMapper.insert(failed);
                throw e;
            }
        }
    }

    /** CONDITION 分流：首个命中边路由；无条件边兜底；不命中丢弃。 */
    private void splitConditional(LinkedHashSet<Long> here, List<WorkflowEdge> outs, List<MemberContext> members,
                                  Map<String, ConditionCompiler.CompiledCondition> conditionCache,
                                  ObjectNode output, Map<String, LinkedHashSet<Long>> arriving) {
        Map<String, Long> routed = new LinkedHashMap<>();
        output.put("contacts", here == null ? 0 : here.size());
        if (here == null || here.isEmpty()) {
            output.set("routed", objectMapper.valueToTree(routed));
            output.put("dropped", 0);
            return;
        }
        Map<Long, MemberContext> byId = new HashMap<>();
        for (MemberContext m : members) {
            byId.put(m.contactId, m);
        }
        // 无条件边（兜底）
        WorkflowEdge elseEdge = null;
        List<WorkflowEdge> condEdges = new ArrayList<>();
        for (WorkflowEdge e : outs) {
            if (isBlankCondition(e.getCondition())) {
                elseEdge = e;
            } else {
                condEdges.add(e);
            }
        }
        Map<String, LinkedHashSet<Long>> branch = new LinkedHashMap<>();
        for (WorkflowEdge e : outs) {
            branch.put(e.getTargetKey(), new LinkedHashSet<>());
        }
        long dropped = 0;
        for (Long id : here) {
            MemberContext m = byId.get(id);
            boolean hit = false;
            for (WorkflowEdge e : condEdges) {
                ConditionCompiler.CompiledCondition c = conditionCache.computeIfAbsent(
                        e.getSourceKey() + "|" + e.getTargetKey(),
                        k -> compiler.compile(e.getCondition()));
                if (compiler.evaluate(c, m.event(), m.contact(), m.history())) {
                    branch.get(e.getTargetKey()).add(id);
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                if (elseEdge != null) {
                    branch.get(elseEdge.getTargetKey()).add(id);
                } else {
                    dropped++;
                }
            }
        }
        for (Map.Entry<String, LinkedHashSet<Long>> x : branch.entrySet()) {
            if (!x.getValue().isEmpty()) {
                routed.put(x.getKey(), (long) x.getValue().size());
                arriving.put(x.getKey(), x.getValue());
            }
        }
        output.set("routed", objectMapper.valueToTree(routed));
        output.put("dropped", dropped);
    }

    private void actionOutput(WorkflowNode node, int contacts, ObjectNode output) {
        JsonNode cfg = parseConfig(node);
        String channel = cfg.path("channel").asText(null);
        Long templateId = cfg.path("templateId").isNumber() ? cfg.path("templateId").asLong() : null;
        if (channel == null || templateId == null) {
            throw new EngineException("ACTION 节点 " + node.getNodeKey() + " 缺少 channel/templateId 配置");
        }
        output.put("channel", channel);
        output.put("templateId", templateId);
        output.put("contacts", contacts);
        JsonNode unitCost = cfg.get("unitCost");
        if (unitCost != null && unitCost.isNumber()) {
            BigDecimal cost = unitCost.decimalValue().multiply(BigDecimal.valueOf(contacts));
            output.put("estimatedCost", cost);
        }
    }

    private int delayMinutes(WorkflowNode node) {
        JsonNode cfg = parseConfig(node);
        JsonNode v = cfg.get("durationMinutes");
        if (v == null || !v.isNumber() || v.asLong() <= 0) {
            v = cfg.get("delayMinutes");
        }
        if (v == null || !v.isNumber() || v.asLong() <= 0) {
            throw new EngineException("DELAY 节点 " + node.getNodeKey() + " 需要 durationMinutes > 0");
        }
        return v.asInt();
    }

    private void passThrough(List<WorkflowEdge> outs, LinkedHashSet<Long> here,
                             Map<String, LinkedHashSet<Long>> arriving) {
        if (here == null || here.isEmpty()) {
            return;
        }
        for (WorkflowEdge e : outs) {
            arriving.put(e.getTargetKey(), here);
        }
    }

    private JsonNode parseConfig(WorkflowNode node) {
        try {
            String cfg = node.getConfig();
            return cfg == null || cfg.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(cfg);
        } catch (Exception e) {
            throw new EngineException("节点 " + node.getNodeKey() + " config 非法: " + e.getMessage(), e);
        }
    }

    private static boolean isBlankCondition(String condition) {
        return condition == null || condition.isBlank() || "{}".equals(condition) || "null".equals(condition);
    }

    private List<String> topoOrder(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (WorkflowNode n : nodes) {
            indegree.put(n.getNodeKey(), 0);
            adj.put(n.getNodeKey(), new ArrayList<>());
        }
        for (WorkflowEdge e : edges) {
            indegree.merge(e.getTargetKey(), 1, Integer::sum);
            adj.get(e.getSourceKey()).add(e.getTargetKey());
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> x : indegree.entrySet()) {
            if (x.getValue() == 0) {
                ready.add(x.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String cur = ready.poll();
            order.add(cur);
            for (String next : adj.get(cur)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        return order;
    }

    private ExecutionReport buildReport(Execution execution, Long workflowId, Integer version,
                                        int totalMembers, String error) {
        List<ExecutionNodeState> states = statesOf(execution.getId());
        List<NodeOutcome> outcomes = new ArrayList<>();
        for (ExecutionNodeState s : states) {
            JsonNode out = null;
            try {
                out = s.getOutput() == null ? null : objectMapper.readTree(s.getOutput());
            } catch (Exception ignored) {
                // output 已由引擎写入，解析失败仅影响详情展示
            }
            outcomes.add(new NodeOutcome(s.getNodeKey(), s.getNodeType(), s.getNodeName(), s.getStatus(),
                    s.getOutput() == null ? 0 : outputContacts(out), out));
        }
        long durationMs = 0;
        if (execution.getStartedAt() != null) {
            Instant end = execution.getFinishedAt() != null ? execution.getFinishedAt() : Instant.now();
            durationMs = java.time.Duration.between(execution.getStartedAt(), end).toMillis();
        }
        return new ExecutionReport(execution.getId(), workflowId, version, execution.getStatus(),
                totalMembers, Boolean.TRUE.equals(execution.getDryRun()), durationMs, error, outcomes);
    }

    private static int outputContacts(JsonNode out) {
        JsonNode c = out.get("contacts");
        if (c != null && c.isNumber()) {
            return c.asInt();
        }
        JsonNode t = out.get("totalMembers");
        if (t != null && t.isNumber()) {
            return t.asInt();
        }
        return 0;
    }
}