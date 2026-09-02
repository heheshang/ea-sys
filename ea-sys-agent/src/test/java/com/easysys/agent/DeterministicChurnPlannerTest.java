package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流失预警规则规划器：规则语义（从未活跃/超期未活跃 → HIGH）与 AgentExecutor 闸门
 * （schema 校验 + 置信度）同路径生效，批量输出自洽通过 churnScanSchema。
 */
class DeterministicChurnPlannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DeterministicChurnPlanner PLANNER = new DeterministicChurnPlanner();
    private static final AgentRunConfig CFG = AgentRunConfig.defaults();

    @Test
    void ruleNeverActiveIsHighRisk() {
        ObjectNode out = assessOne(null, 30);
        assertEquals(90, out.path("churn_risk").asInt());
        assertEquals("HIGH", out.path("tier").asText());
        assertEquals(1, out.path("drivers").size());
        assertEquals("从未活跃", out.path("drivers").get(0).asText());
    }

    @Test
    void ruleOverThresholdIsHighRisk() {
        ObjectNode out = assessOne(31, 30);
        assertEquals(75, out.path("churn_risk").asInt());
        assertEquals("HIGH", out.path("tier").asText());
        assertEquals("31天未活跃", out.path("drivers").get(0).asText());
    }

    @Test
    void ruleWithinThresholdIsLowRisk() {
        ObjectNode out = assessOne(30, 30);
        assertEquals(5, out.path("churn_risk").asInt());
        assertEquals("LOW", out.path("tier").asText());
        assertEquals(0, out.path("drivers").size());
    }

    @Test
    void batchOutputConformsToSchemaAndSummarizes() {
        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode contacts = input.putArray("contacts");
        ObjectNode never = contacts.addObject();
        never.put("contact_id", 1L);
        never.putNull("inactive_days");
        ObjectNode stale = contacts.addObject();
        stale.put("contact_id", 2L);
        stale.put("inactive_days", 45);
        ObjectNode active = contacts.addObject();
        active.put("contact_id", 3L);
        active.put("inactive_days", 2);
        input.put("threshold_days", 30);

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "churn_scan", input, CFG);
        assertEquals("SUCCESS", outcome.status());
        assertNotNull(outcome.output());
        assertTrue(LayerSchemas.churnScanSchema().contains("$schema"));
        assertEquals(AgentType.CHURN, outcome.audit().agentType());
        assertEquals("churn_scan", outcome.audit().action());

        JsonNode results = outcome.output().path("results");
        assertEquals(3, results.size());
        assertEquals("HIGH", results.get(0).path("tier").asText());
        assertEquals("HIGH", results.get(1).path("tier").asText());
        assertEquals("LOW", results.get(2).path("tier").asText());

        JsonNode summary = outcome.output().path("summary");
        assertEquals(3, summary.path("scanned").asInt());
        assertEquals(2, summary.path("HIGH").asInt());
        assertEquals(0, summary.path("MEDIUM").asInt());
        assertEquals(1, summary.path("LOW").asInt());
    }

    @Test
    void batchThresholdDefaultsAndRespectsOverride() {
        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode contacts = input.putArray("contacts");
        ObjectNode c1 = contacts.addObject();
        c1.put("contact_id", 1L);
        c1.put("inactive_days", 30);
        input.put("threshold_days", 7);

        JsonNode out = PLANNER.plan(input);
        assertEquals("HIGH", out.path("results").get(0).path("tier").asText());

        ObjectNode defaultInput = MAPPER.createObjectNode();
        ArrayNode contacts2 = defaultInput.putArray("contacts");
        ObjectNode c2 = contacts2.addObject();
        c2.put("contact_id", 1L);
        c2.put("inactive_days", 30);
        JsonNode out2 = PLANNER.plan(defaultInput);
        assertEquals("LOW", out2.path("results").get(0).path("tier").asText());
    }

    @Test
    void singleInputUsesChurnRiskShape() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("inactive_days", 45);
        input.put("threshold_days", 30);
        JsonNode out = PLANNER.plan(input);
        assertEquals(75, out.path("churn_risk").asInt());
        assertEquals("HIGH", out.path("tier").asText());
        assertTrue(out.path("drivers").isArray());
        // 单项输出即单成员 schema 形状（churn_risk/tier/drivers，无批量 results/summary）
        assertEquals(3, out.size());
        assertTrue(out.path("results").isMissingNode());
    }

    private static ObjectNode assessOne(Integer inactiveDays, int threshold) {
        ObjectNode input = MAPPER.createObjectNode();
        if (inactiveDays == null) {
            input.putNull("inactive_days");
        } else {
            input.put("inactive_days", inactiveDays);
        }
        input.put("threshold_days", threshold);
        return (ObjectNode) PLANNER.plan(input);
    }
}