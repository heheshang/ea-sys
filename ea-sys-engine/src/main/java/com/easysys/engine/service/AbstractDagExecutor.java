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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

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
 * DAG 执行器公共骨架：拓扑序推进状态机，CONDITION 按出边 DSL 分流，DELAY/UPDATE/END/TRIGGER
 * 公共处理，节点状态落库（execution + execution_node_state，一行/节点），报告由执行结果动态聚合。
 *
 * ACTION 节点交由子类 {@link #handleAction} 决策：干跑估算接触人数，真实执行渲染模板并发起下发。
 *
 * 语义：
 * - 批量模式：成员集合 = 快照画像快照（event 上下文为空 map，事件触发模式留待后续里程碑）
 * - CONDITION：按出边顺序首个条件命中 → 路由；无条件出边为兜底；全不命中 → 丢弃
 * - 节点异常 → 该节点 FAILED、执行 FAILED 并提交（报告可见病根），后续节点不再执行
 */
public abstract class AbstractDagExecutor {

    protected final ExecutionMapper executionMapper;
    protected final ExecutionNodeStateMapper stateMapper;
    protected final ConditionCompiler compiler;
    protected final ObjectProvider<AgentSplitHandler> agentSplitHandler;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractDagExecutor(ExecutionMapper executionMapper, ExecutionNodeStateMapper stateMapper,
                                  ConditionCompiler compiler, ObjectProvider<AgentSplitHandler> agentSplitHandler) {
        this.executionMapper = executionMapper;
        this.stateMapper = stateMapper;
        this.compiler = compiler;
        this.agentSplitHandler = agentSplitHandler;
    }

    /** 成员画像上下文（event 触发模式后续里程碑使用，批量干跑/执行为空） */
    public record MemberContext(Long contactId,
                                Map<String, Object> contact,
                                Map<String, Object> history,
                                Map<String, Object> event) {
    }

    /** 单节点执行结果（node_state 行 + 报告条目同构） */
    public record NodeOutcome(String nodeKey, String nodeType, String nodeName, String status,
                              int contacts, JsonNode output) {
    }

    /** 执行报告（动态聚合，无独立表） */
    public record ExecutionReport(Long executionId, Long workflowId, Integer workflowVersion, String status,
                                  int totalMembers, boolean dryRun, long durationMs, String error,
                                  List<NodeOutcome> nodes) {
    }

    /**
     * 执行一次 DAG：dryRun=true 时 ACTION 仅估算人数（DryRunExecutor），
     * false 时真实渲染模板并按通道下发（WorkflowExecutor）。
     */
    /** 手动触达执行（状态机共用，triggerType=MANUAL）。 */
    @Transactional
    public ExecutionReport execute(Workflow workflow, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                   Long audienceSnapshotId, List<MemberContext> members, boolean dryRun) {
        return execute(workflow, nodes, edges, audienceSnapshotId, members, dryRun,
                TriggerType.MANUAL.name(), null);
    }

    /** 显式触发执行（定时/事件/API/手动统一入口）。triggerPayload 记录触发上下文（调度时间/事件载荷等）。 */
    @Transactional
    public ExecutionReport execute(Workflow workflow, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                   Long audienceSnapshotId, List<MemberContext> members, boolean dryRun,
                                   String triggerType, String triggerPayload) {
        Instant startedAt = Instant.now();
        Long tenantId = TenantContext.require();
        Long workflowId = workflow.getRefId() != null ? workflow.getRefId() : workflow.getId();
        Integer version = workflow.getVersion();

        Execution execution = new Execution();
        execution.setTenantId(tenantId);
        execution.setWorkflowId(workflowId);
        execution.setWorkflowVersion(version);
        execution.setTriggerType(triggerType != null ? triggerType : TriggerType.MANUAL.name());
        execution.setTriggerPayload(triggerPayload);
        execution.setAudienceSnapshotId(audienceSnapshotId);
        execution.setDryRun(dryRun);
        execution.setStatus(ExecutionStatus.RUNNING.name());
        execution.setStartedAt(startedAt);
        execution.setCreatedAt(startedAt);
        execution.setUpdatedAt(startedAt);
        executionMapper.insert(execution);
        Long executionId = execution.getId();

        String error = null;
        try {
            runNodes(executionId, tenantId, nodes, edges, members, dryRun);
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

    /**
     * ACTION 节点（子类实现）：干跑估算接触人数；真实执行模板渲染 + 通道下发。
     * executionId 供下发记录/幂等键使用；byId 为全体成员画像索引（含未达本节点成员）。
     */
    protected abstract void handleAction(Long executionId, WorkflowNode node, LinkedHashSet<Long> here,
                                         Map<Long, MemberContext> byId, ObjectNode output);

    /** 节点 config 解析（非法 JSON 抛错置节点 FAILED）。 */
    protected JsonNode parseConfig(WorkflowNode node) {
        try {
            String cfg = node.getConfig();
            return cfg == null || cfg.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(cfg);
        } catch (Exception e) {
            throw new EngineException("节点 " + node.getNodeKey() + " config 非法: " + e.getMessage(), e);
        }
    }

    protected List<ExecutionNodeState> statesOf(Long executionId) {
        return stateMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExecutionNodeState>()
                        .eq(ExecutionNodeState::getExecutionId, executionId)
                        .orderByAsc(ExecutionNodeState::getId));
    }

    protected static boolean isBlankCondition(String condition) {
        return condition == null || condition.isBlank() || "{}".equals(condition) || "null".equals(condition);
    }

    private void runNodes(Long executionId, Long tenantId, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                          List<MemberContext> members, boolean dryRun) {
        if (members == null) {
            members = List.of();
        }
        // 由全体成员初始到达 TRIGGER（抽样后续里程碑接入）
        LinkedHashSet<Long> allIds = new LinkedHashSet<>();
        Map<Long, MemberContext> byId = new HashMap<>();
        for (MemberContext m : members) {
            allIds.add(m.contactId);
            byId.put(m.contactId, m);
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
                    splitConditional(here, outs, byId, conditionCache, output, arriving);
                } else if (type == NodeType.AGENT_SPLIT) {
                    AgentSplitHandler split = agentSplitHandler.getIfAvailable();
                    if (split == null) {
                        throw new EngineException("AGENT_SPLIT 节点需要分层处理器（api 层接入）: " + node.getNodeKey());
                    }
                    Map<String, LinkedHashSet<Long>> routed = split.split(
                            executionId, node, here, byId, outs, output, dryRun);
                    for (Map.Entry<String, LinkedHashSet<Long>> x : routed.entrySet()) {
                        arriving.put(x.getKey(), x.getValue());
                    }
                } else if (type == NodeType.ACTION) {
                    handleAction(executionId, node, here, byId, output);
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
    private void splitConditional(LinkedHashSet<Long> here, List<WorkflowEdge> outs,
                                  Map<Long, MemberContext> byId,
                                  Map<String, ConditionCompiler.CompiledCondition> conditionCache,
                                  ObjectNode output, Map<String, LinkedHashSet<Long>> arriving) {
        Map<String, Long> routed = new LinkedHashMap<>();
        output.put("contacts", here == null ? 0 : here.size());
        if (here == null || here.isEmpty()) {
            output.set("routed", objectMapper.valueToTree(routed));
            output.put("dropped", 0);
            return;
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