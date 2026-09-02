package com.easysys.engine.service;

import com.easysys.engine.entity.WorkflowEdge;
import com.easysys.engine.entity.WorkflowNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * AGENT_SPLIT 节点分流处理器：由 api 层实现（分层策略 + 通道可达性 + 审计），
 * 引擎只定义契约与传播语义。返回 targetKey → 到达成员映射（扇出：一个成员可进多条出边），
 * 引擎将各分支写入 arriving 继续拓扑推进。
 *
 * 语义（docs/04-agent-design.md §4）：
 * - 每个到达成员按确定性分层策略计算 layer（contact.layer 注入画像上下文后由出边 DSL 判定）
 * - 出边条件基于 contact.layer（如 eq "L1"），无条件出边为兜底（无通道层）
 * - dryRun=true 时不允许写 contact_attribute / audit_log（干跑只估算路由）
 */
public interface AgentSplitHandler {

    Map<String, LinkedHashSet<Long>> split(Long executionId, WorkflowNode node, LinkedHashSet<Long> here,
                                           Map<Long, AbstractDagExecutor.MemberContext> byId,
                                           List<WorkflowEdge> outs, ObjectNode output, boolean dryRun);
}