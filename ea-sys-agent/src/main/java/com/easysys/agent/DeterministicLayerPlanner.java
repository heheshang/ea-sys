package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 确定性分层规划器（fallback 主实现）：按通道可达性把人群拆为 L1~L4 四层，
 * 双通道成员按 DAG 顺序依次走两个通道（route_order 原序）。输出自洽满足
 * {@link LayerSchemas#strategySchema()}，可作为智能体主提供方或兜底提供方。
 *
 * 入参：
 * <pre>
 * {"strategy_version": "20260902-1", "route_order": ["sms", "email"]}
 * </pre>
 * route_order 缺省 ["sms", "email"]；通道枚举当前为 sms / email（channel_availability 维度）。
 */
public final class DeterministicLayerPlanner implements StrategyAgent, AgentFallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public AgentType type() {
        return AgentType.LAYER;
    }

    @Override
    public String schema() {
        return LayerSchemas.strategySchema();
    }

    @Override
    public JsonNode plan(JsonNode input) {
        return build(input);
    }

    @Override
    public JsonNode fallback(JsonNode input) {
        return build(input);
    }

    /** 兜底默认分层：双通道先短信后邮件（架构文档通道优先语义）。 */
    public JsonNode defaultStrategy() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("strategy_version", "default");
        return build(input);
    }

    private static JsonNode build(JsonNode input) {
        String version = input.path("strategy_version").asText("default");
        List<String> order = routeOrder(input.path("route_order"));

        ObjectNode out = MAPPER.createObjectNode();
        out.put("strategy_version", version);
        ArrayNode dims = out.putArray("dimensions");
        dims.add("channel_availability");
        ArrayNode layers = out.putArray("layers");
        layers.add(layer("L1", "仅短信", "sms_only", List.of("sms")));
        layers.add(layer("L2", "仅邮件", "email_only", List.of("email")));
        layers.add(layer("L3", "双通道", "multi", order));
        layers.add(layer("L4", "无通道", "none", List.of()));

        ObjectNode fallback = out.putObject("fallback_rule");
        fallback.put("channel_availability", "sms_only");
        ArrayNode fbOrder = fallback.putArray("route_order");
        fbOrder.add("sms");

        out.put("source", "deterministic");
        out.put("auditable", true);
        out.put("confidence", 1.0);
        return out;
    }

    private static ObjectNode layer(String id, String name, String availability, List<String> routeOrder) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("name", name);
        ObjectNode rule = node.putObject("rule");
        rule.put("channel_availability", availability);
        ArrayNode order = node.putArray("route_order");
        routeOrder.forEach(order::add);
        int priority = switch (availability) {
            case "sms_only" -> 1;
            case "email_only" -> 2;
            case "multi" -> 3;
            default -> 4;
        };
        node.put("priority", priority);
        node.put("confidence", 1.0);
        node.put("rationale", switch (availability) {
            case "sms_only" -> "仅手机号可触达";
            case "email_only" -> "仅邮箱可触达";
            case "multi" -> "双通道按 DAG 顺序依次触达";
            default -> "无可用通道，不触达";
        });
        return node;
    }

    /** 通道顺序：入参给定则校验去重保序，否则默认短信 → 邮件。 */
    private static List<String> routeOrder(JsonNode routeOrder) {
        if (routeOrder == null || !routeOrder.isArray() || routeOrder.isEmpty()) {
            return List.of("sms", "email");
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (JsonNode c : routeOrder) {
            if (c.isTextual()) {
                String v = c.asText();
                if (v.equals("sms") || v.equals("email")) {
                    seen.add(v);
                }
            }
        }
        if (seen.isEmpty()) {
            return List.of("sms", "email");
        }
        return List.copyOf(seen);
    }
}