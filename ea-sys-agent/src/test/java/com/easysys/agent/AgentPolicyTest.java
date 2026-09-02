package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AgentPolicy 闸门语义（执行面由 HarnessAgent 框架承载）：主提供方失败 / schema 不符 /
 * 低置信 → 确定性兜底且执行不中断。确定性规划器输出必须自洽通过自身 schema
 * （LLM 接入后同一条硬校验路径即刻生效）。
 */
class AgentPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DeterministicLayerPlanner PLANNER = new DeterministicLayerPlanner();
    private static final AgentRunConfig CFG = AgentRunConfig.defaults();
    /** LLM 模型位测试的超时（qwen3.7-plus reasoning 响应 ~20s，与生产 llm.timeoutMs 对齐）。 */
    private static final AgentRunConfig LLM_CFG = new AgentRunConfig(0.7, 2, 120_000);

    private static ObjectNode input() {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("strategy_version", "unit-test-1");
        return in;
    }

    @Test
    void plannerOutputConformsToSchemaAndSucceeds() {
        JsonNode in = input();
        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.deterministic(PLANNER), PLANNER, PLANNER, "unit", in, CFG);
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

        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.deterministic(broken), broken, PLANNER, "unit", input(), CFG);
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

        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.deterministic(alwaysThrows), alwaysThrows, PLANNER, "unit", input(), CFG);
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

        StrategyAgent shakyPrimary = new StrategyAgent() {
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
        };
        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.deterministic(shakyPrimary), shakyPrimary, PLANNER, "unit", input(), CFG);
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

        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.deterministic(broken), broken, badFallback, "unit", input(), CFG);
        assertEquals("ERROR", outcome.status());
        assertEquals("fallback_invalid", outcome.reason());
        assertEquals("ERROR", outcome.audit().status());
    }

    private static final String LLM_BASE_URL = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

    /**
     * LLM 真连通：真实 key（env EA_LLM_API_KEY）走 qwen3.7-plus，断言主提供方输出过自身 schema。
     * 无 key 时跳过（CI/本机默认不配，确定性链路不受影响）。harness 模型位即 LLM（与生产装配一致）。
     */
    @Test
    void llmPrimarySucceedsWhenConfigured() {
        String apiKey = System.getenv("EA_LLM_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "EA_LLM_API_KEY 未配置，跳过 LLM 真连通测试");
        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.llm(LLM_BASE_URL, apiKey),
                PLANNER, PLANNER, "llm_unit", input(), LLM_CFG);
        assertEquals("SUCCESS", outcome.status());
        assertNotNull(outcome.output());
        // LLM 输出过自身 schema 即可（层数由模型决定，非确定性固定契约）
        assertTrue(outcome.output().path("layers").isArray());
        assertTrue(outcome.output().path("layers").size() >= 1);
        // 审计记录真实模型
        assertEquals("openai:qwen3.7-plus", outcome.audit().model());
    }

    /**
     * LLM 全挂（不可达端点）→ provider_error 落入确定性 fallback，执行不中断 —— 核心质保。
     * 无 key 也成立：显式注入假 key 触发真实网络失败路径（模型位即 LLM）。
     */
    @Test
    void llmDownFallsBackToDeterministic() {
        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.llm("http://127.0.0.1:9/v1", "fake-key"),
                PLANNER, PLANNER, "llm_down_unit", input(), CFG);
        assertEquals("FALLBACK", outcome.status());
        assertTrue(outcome.reason().startsWith("provider_error:"), "reason: " + outcome.reason());
        // 确定性 fallback 结果完整可用
        assertNotNull(outcome.output());
        assertEquals("L1", outcome.output().path("layers").get(0).path("id").asText());
        assertEquals(4, outcome.output().path("layers").size());
    }

    /**
     * LLM 未开启（装配层判定：enabled + apiKey 缺失 → 确定性模型位），行为同 M6。
     */
    @Test
    void llmInactiveWithoutApiKeyKeepsDeterministic() {
        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.deterministic(PLANNER),
                PLANNER, PLANNER, "llm_inactive_unit", input(), CFG);
        assertEquals("SUCCESS", outcome.status());
        assertEquals("deterministic", outcome.audit().model());
    }

    @Test
    void routeDecisionSchemaAcceptsConformingDecision() {
        ObjectNode decision = MAPPER.createObjectNode();
        decision.put("layer", "L3");
        decision.putArray("channels").add("sms").add("email");
        decision.putArray("route_order").add("sms").add("email");
        decision.put("skip", false);
        decision.put("confidence", 1.0);
        StrategyAgent router = new StrategyAgent() {
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
        };
        AgentOutcome outcome = AgentPolicy.run(BatchTestAgents.deterministic(router), router, PLANNER, "unit", input(), CFG);
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