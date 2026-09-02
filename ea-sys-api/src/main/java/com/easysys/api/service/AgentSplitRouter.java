package com.easysys.api.service;

import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.LayerStrategy;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.engine.entity.WorkflowEdge;
import com.easysys.engine.entity.WorkflowNode;
import com.easysys.engine.service.AbstractDagExecutor;
import com.easysys.engine.service.AgentSplitHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * AGENT_SPLIT 分层路由处理器（确定性降级主路径）：
 * - 每成员按通道可达性（手机号/邮箱 + 退订渠道）计算层 L1~L4
 * - 出边条件基于 contact.layer（如 {"op":"equals","field":"contact.layer","value":"L1"}）→ 该层路由
 * - 无条件出边 = 无通道兜底（L4 或画布未配置该层时收容）
 * - dryRun=true 不写 contact_attribute / audit_log，只估算
 * - 真实执行时打标 layer + 写 route_split 审计（引用生效策略版本）
 */
@Component
public class AgentSplitRouter implements AgentSplitHandler {

    private static final List<String> LAYER_IDS = List.of("L1", "L2", "L3", "L4");

    private final LayerTagger layerTagger;
    private final StrategyService strategyService;
    private final AgentAuditMapper auditMapper;
    private final ObjectMapper json;

    public AgentSplitRouter(LayerTagger layerTagger, StrategyService strategyService,
                            AgentAuditMapper auditMapper, ObjectMapper json) {
        this.layerTagger = layerTagger;
        this.strategyService = strategyService;
        this.auditMapper = auditMapper;
        this.json = json;
    }

    @Override
    public Map<String, LinkedHashSet<Long>> split(Long executionId, WorkflowNode node, LinkedHashSet<Long> here,
                                                  Map<Long, AbstractDagExecutor.MemberContext> byId,
                                                  List<WorkflowEdge> outs, ObjectNode output, boolean dryRun) {
        Long tenantId = TenantContext.require();
        LayerStrategy active = strategyService.activeStrategy();
        String strategyVersion = active == null ? "default" : active.getStrategyVersion();
        String source = active == null ? "deterministic" : active.getSource();
        int count = here == null ? 0 : here.size();

        WorkflowEdge elseEdge = null;
        Map<String, WorkflowEdge> layerEdges = new HashMap<>();
        for (WorkflowEdge e : outs) {
            String layer = layerOf(e.getCondition());
            if (layer == null) {
                elseEdge = e;
            } else {
                layerEdges.put(layer, e);
            }
        }

        Map<String, Long> byLayer = new LinkedHashMap<>();
        for (String l : LAYER_IDS) {
            byLayer.put(l, 0L);
        }
        Map<String, LinkedHashSet<Long>> byTarget = new LinkedHashMap<>();
        Map<Long, String> marks = new LinkedHashMap<>();
        long dropped = 0;
        if (here != null) {
            for (Long id : here) {
                AbstractDagExecutor.MemberContext m = byId.get(id);
                String layer = m == null ? "L4" : layerFor(m);
                byLayer.merge(layer, 1L, Long::sum);
                WorkflowEdge target = layerEdges.get(layer);
                if (target == null) {
                    target = elseEdge;
                }
                if (target == null) {
                    dropped++;
                    continue;
                }
                byTarget.computeIfAbsent(target.getTargetKey(), k -> new LinkedHashSet<>()).add(id);
                marks.put(id, layer);
            }
        }

        output.put("contacts", count);
        output.set("byLayer", json.valueToTree(byLayer));
        ObjectNode routed = output.putObject("routed");
        byTarget.forEach((k, v) -> routed.put(k, v.size()));
        output.put("dropped", dropped);
        output.put("strategy_version", strategyVersion);

        if (!dryRun && !marks.isEmpty()) {
            layerTagger.mark(tenantId, marks);
        }
        if (!dryRun) {
            writeAudit(tenantId, executionId, node.getNodeKey(), count, byTarget, byLayer,
                    dropped, strategyVersion, source);
        }
        return byTarget;
    }

    /** 出边条件 → 层选择器；非 layer 匹配（空/其他 DSL）→ null（无条件兜底）。 */
    private String layerOf(String condition) {
        JsonNode cond = parse(condition);
        if (cond == null) {
            return null;
        }
        return layerFrom(cond);
    }

    /** 递归查找 contact.layer 条件项（兼容 AND/OR 包装），返回层值；找不到 → null。 */
    private String layerFrom(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (isLogical(node)) {
            JsonNode items = node.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String layer = layerFrom(item);
                    if (layer != null) {
                        return layer;
                    }
                }
            }
            return null;
        }
        JsonNode v = node.path("value");
        if (node.path("field").asText().equals("contact.layer")
                && node.path("op").asText().equals("equals") && v.isTextual()) {
            return v.asText();
        }
        JsonNode layer = node.path("layer");
        return layer.isTextual() ? layer.asText() : null;
    }

    private boolean isLogical(JsonNode node) {
        String op = node.path("op").asText();
        return "AND".equals(op) || "OR".equals(op);
    }

    /** 通道可达性 → 层：双通道 L3 / 仅短信 L1 / 仅邮件 L2 / 无通道 L4（退订渠道不可达）。 */
    private String layerFor(AbstractDagExecutor.MemberContext m) {
        Map<String, Object> ctx = m.contact();
        boolean sms = reachable(ctx, "phone", "sms");
        boolean email = reachable(ctx, "email", "email");
        if (sms && email) {
            return "L3";
        }
        if (sms) {
            return "L1";
        }
        if (email) {
            return "L2";
        }
        return "L4";
    }

    private boolean reachable(Map<String, Object> ctx, String field, String channel) {
        Object raw = ctx.get(field);
        if (!(raw instanceof String s) || s == null || s.isBlank()) {
            return false;
        }
        Object sup = ctx.get("suppressedChannels");
        if (sup instanceof List<?> list) {
            for (Object x : list) {
                if (x != null && (x.toString().equals(channel) || x.toString().equals("*"))) {
                    return false;
                }
            }
        }
        return true;
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = json.readTree(raw);
            return n == null || n.isNull() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeAudit(Long tenantId, Long executionId, String nodeKey, int count,
                            Map<String, LinkedHashSet<Long>> byTarget, Map<String, Long> byLayer,
                            long dropped, String strategyVersion, String source) {
        ObjectNode input = json.createObjectNode();
        input.put("execution_id", executionId);
        input.put("node_key", nodeKey);
        input.put("contacts", count);
        input.put("strategy_version", strategyVersion);
        input.put("source", source);

        ObjectNode out = json.createObjectNode();
        out.set("byLayer", json.valueToTree(byLayer));
        ObjectNode routed = out.putObject("routed");
        byTarget.forEach((k, v) -> routed.put(k, v.size()));
        out.put("dropped", dropped);

        AgentAudit a = new AgentAudit();
        a.setTenantId(tenantId);
        a.setAgentType("LAYER");
        a.setAction("route_split");
        a.setStatus("SUCCESS");
        a.setInputSummary(input.toString());
        a.setOutput(out.toString());
        a.setSchemaValid(true);
        a.setStrategyVersion(strategyVersion);
        a.setConfidence(java.math.BigDecimal.ONE);
        a.setModel("deterministic");
        a.setDurationMs(0L);
        a.setCreatedAt(Instant.now());
        auditMapper.insert(a);
    }
}