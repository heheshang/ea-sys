package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentExecutor 闸门语义：主提供方失败 / schema 不符 / 低置信 → 确定性兜底且执行不中断。
 * 确定性规划器输出必须自洽通过自身 schema（LLM 接入后同一条硬校验路径即刻生效）。
 */
class AgentExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DeterministicLayerPlanner PLANNER = new DeterministicLayerPlanner();
    private static final AgentRunConfig CFG = AgentRunConfig.defaults();

    private static ObjectNode input() {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("strategy_version", "unit-test-1");
        return in;
    }

    @Test
    void plannerOutputConformsToSchemaAndSucceeds() {
        JsonNode in = input();
        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "unit", in, CFG);
        assertEquals("SUCCESS", outcome.status());
        assertNotNull(outcome.output());
        assertEquals("L1", outcome.output().path("layers").get(0).path("id").asText());
        assertEquals(4, outcome.output().path("layers").size());
        assertEquals(1.0, outcome.audit().confidence());
        assertEquals(AgentType.LAYER, outcome.audit().agentType());
        assertTrue(outcome.audit().durationMs() >= 0);
        // 版本透传
        assertEquals("unit-test-1", outcome.output().path("strategy_version").asText());
    }

    @Test
    void schemaInvalidPrimaryFallsBackToDeterministic() {
        ObjectNode invalid = input();
        invalid.putArray("layers"); // minItems 1 违规
        invalid.put("confidence", 1.0);
        invalid.put("source", "deterministic");

        StrategyAgent broken = new StrategyAgent() {
            @Override
            public AgentType type() {
                return AgentType.LAYER;
            }

            @Override
            public String schema() {
                return LayerSchemas.strategySchema();
            }

            @Override
            public JsonNode plan(JsonNode in) {
                return invalid;
            }
        };

        AgentOutcome outcome = AgentExecutor.run(broken, PLANNER, "unit", input(), CFG);
        assertEquals("FALLBACK", outcome.status());
        assertEquals("schema_invalid", outcome.reason());
        assertEquals("L1", outcome.output().path("layers").get(0).path("id").asText());
        assertEquals("FALLBACK", outcome.audit().status());
        assertEquals("schema_invalid", outcome.audit().reason());
    }

    @Test
    void throwingPrimaryFallsBackAfterRetries() {
        StrategyAgent alwaysThrows = new StrategyAgent() {
            @Override
            public AgentType type() {
                return AgentType.LAYER;
            }

            @Override
            public String schema() {
                return LayerSchemas.strategySchema();
            }

            @Override
            public JsonNode plan(JsonNode in) {
                throw new IllegalStateException("llm unreachable");
            }
        };

        AgentOutcome outcome = AgentExecutor.run(alwaysThrows, PLANNER, "unit", input(), CFG);
        assertEquals("FALLBACK", outcome.status());
        assertTrue(outcome.reason().startsWith("provider_error:"));
        assertNotNull(outcome.output());
    }

    @Test
    void lowConfidenceFallsBackToDeterministic() {
        ObjectNode shaky = input();
        ObjectNode fb = MAPPER.createObjectNode();
        fb.put("channel_availability", "sms_only");
        fb.putArray("route_order").add("sms");
        shaky.set("fallback_rule", fb);
        shaky.putArray("layers").add(shakyLayer());
        shaky.putArray("dimensions").add("channel_availability");
        shaky.put("source", "deterministic");
        shaky.put("confidence", 0.3); // 低于默认 0.7 阈值

        AgentOutcome outcome = AgentExecutor.run(new StrategyAgent() {
            @Override
            public AgentType type() {
                return AgentType.LAYER;
            }

            @Override
            public String schema() {
                return LayerSchemas.strategySchema();
            }

            @Override
            public JsonNode plan(JsonNode in) {
                return shaky;
            }
        }, PLANNER, "unit", input(), CFG);
        assertEquals("FALLBACK", outcome.status());
        assertEquals("low_confidence", outcome.reason());
        assertEquals(1.0, outcome.audit().confidence());
    }

    @Test
    void fallbackOutputAlsoValidated() {
        StrategyAgent broken = new StrategyAgent() {
            @Override
            public AgentType type() {
                return AgentType.ROUTER;
            }

            @Override
            public String schema() {
                return LayerSchemas.routeDecisionSchema();
            }

            @Override
            public JsonNode plan(JsonNode in) {
                throw new IllegalStateException("boom");
            }
        };
        AgentFallback badFallback = in -> MAPPER.createObjectNode().put("layer", "L1"); // 缺必填字段

        AgentOutcome outcome = AgentExecutor.run(broken, badFallback, "unit", input(), CFG);
        assertEquals("ERROR", outcome.status());
        assertEquals("fallback_invalid", outcome.reason());
        assertEquals("ERROR", outcome.audit().status());
    }

    @Test
    void routeDecisionSchemaAcceptsConformingDecision() {
        ObjectNode decision = MAPPER.createObjectNode();
        decision.put("layer", "L3");
        decision.putArray("channels").add("sms").add("email");
        decision.putArray("route_order").add("sms").add("email");
        decision.put("skip", false);
        decision.put("confidence", 1.0);
        AgentOutcome outcome = AgentExecutor.run(new StrategyAgent() {
            @Override
            public AgentType type() {
                return AgentType.ROUTER;
            }

            @Override
            public String schema() {
                return LayerSchemas.routeDecisionSchema();
            }

            @Override
            public JsonNode plan(JsonNode in) {
                return decision;
            }
        }, PLANNER, "unit", input(), CFG);
        assertEquals("SUCCESS", outcome.status());
    }

    private static ObjectNode shakyLayer() {
        ObjectNode layer = MAPPER.createObjectNode();
        layer.put("id", "L1");
        layer.put("name", "仅短信");
        ObjectNode rule = layer.putObject("rule");
        rule.put("channel_availability", "sms_only");
        layer.putArray("route_order").add("sms");
        layer.put("priority", 1);
        layer.put("confidence", 0.3);
        return layer;
    }
}