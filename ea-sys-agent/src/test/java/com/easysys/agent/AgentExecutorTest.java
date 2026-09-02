package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    private static final String LLM_BASE_URL = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

    /**
     * LLM 真连通：真实 key（env EA_LLM_API_KEY）走 qwen3.7-plus，断言主提供方输出过自身 schema。
     * 无 key 时跳过（CI/本机默认不配，确定性链路不受影响）。静态 llmConfig 跨测试共享，finally 恢复 disabled。
     */
    @Test
    void llmPrimarySucceedsWhenConfigured() {
        String apiKey = System.getenv("EA_LLM_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "EA_LLM_API_KEY 未配置，跳过 LLM 真连通测试");
        AgentExecutor.configureLlm(new AgentLlmConfig(true, "openai:qwen3.7-plus", LLM_BASE_URL, apiKey, 120_000));
        try {
            AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "llm_unit", input(), CFG);
            assertEquals("SUCCESS", outcome.status());
            assertNotNull(outcome.output());
            // LLM 输出过自身 schema 即可（层数由模型决定，非确定性固定契约）
            assertTrue(outcome.output().path("layers").isArray());
            assertTrue(outcome.output().path("layers").size() >= 1);
            // 审计记录真实模型
            assertEquals("openai:qwen3.7-plus", outcome.audit().model());
        } finally {
            AgentExecutor.configureLlm(null);
        }
    }

    /**
     * LLM 全挂（不可达端点）→ provider_error 落入确定性 fallback，执行不中断 —— 核心质保。
     * 无 key 也成立：active() 要求 apiKey 非空，此场景显式注入假 key 触发真实网络失败路径。
     */
    @Test
    void llmDownFallsBackToDeterministic() {
        AgentExecutor.configureLlm(new AgentLlmConfig(true, "openai:qwen3.7-plus", "http://127.0.0.1:9/v1", "fake-key", 5_000));
        try {
            AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "llm_down_unit", input(), CFG);
            assertEquals("FALLBACK", outcome.status());
            assertTrue(outcome.reason().startsWith("provider_error:"), "reason: " + outcome.reason());
            // 确定性 fallback 结果完整可用
            assertNotNull(outcome.output());
            assertEquals("L1", outcome.output().path("layers").get(0).path("id").asText());
            assertEquals(4, outcome.output().path("layers").size());
        } finally {
            AgentExecutor.configureLlm(null);
        }
    }

    /**
     * LLM 已配置但 apiKey 缺失 → active()=false，主提供方保持确定性，行为同 M6。
     */
    @Test
    void llmInactiveWithoutApiKeyKeepsDeterministic() {
        AgentExecutor.configureLlm(new AgentLlmConfig(true, "openai:qwen3.7-plus", LLM_BASE_URL, "", 5_000));
        try {
            AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "llm_inactive_unit", input(), CFG);
            assertEquals("SUCCESS", outcome.status());
            assertEquals("deterministic", outcome.audit().model());
        } finally {
            AgentExecutor.configureLlm(null);
        }
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