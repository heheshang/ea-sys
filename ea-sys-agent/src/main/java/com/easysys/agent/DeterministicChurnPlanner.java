package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 确定性流失预警规划器（规则主实现/兜底）：按「N 天未活跃 = HIGH」规则评估流失风险。
 * 支持批量人群输入（churn_scan：一次决策 → 一条审计，输出含聚合 summary）；
 * 也支持单成员输入（churn_assess/测试）。输出自洽满足
 * {@link LayerSchemas#churnScanSchema()} / {@link LayerSchemas#churnRiskSchema()}。
 *
 * 批量入参：
 * <pre>
 * {"contacts": [{"contact_id": 42, "inactive_days": 45}, ...], "threshold_days": 30}
 * </pre>
 * <ul>
 *   <li>inactive_days：距最近活跃的事件天数；null/缺省视为从未活跃</li>
 *   <li>threshold_days：流失判定阈值（默认 30，docs/04-agent-design.md §5「N 天未活跃 = HIGH」）</li>
 * </ul>
 * 规则：从未活跃 → HIGH(90)；inactive_days &gt; threshold → HIGH(75)；否则 → LOW(5)。
 * MEDIUM 为 LLM 接入预留，规则版不产出。
 */
public final class DeterministicChurnPlanner implements StrategyAgent, AgentFallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_THRESHOLD = 30;

    @Override
    public AgentType type() {
        return AgentType.CHURN;
    }

    /** 框架校验用批量 schema（churn_scan 主路径）。 */
    @Override
    public String schema() {
        return LayerSchemas.churnScanSchema();
    }

    @Override
    public JsonNode plan(JsonNode input) {
        return assess(input);
    }

    @Override
    public JsonNode fallback(JsonNode input) {
        return assess(input);
    }

    private static JsonNode assess(JsonNode input) {
        if (input != null && input.has("contacts")) {
            return assessBatch(input);
        }
        return assessOne(input, null);
    }

    private static JsonNode assessBatch(JsonNode input) {
        JsonNode contacts = input.path("contacts");
        int threshold = input.path("threshold_days").isMissingNode()
                ? DEFAULT_THRESHOLD : input.path("threshold_days").asInt(DEFAULT_THRESHOLD);

        ArrayNode results = MAPPER.createArrayNode();
        int high = 0;
        int medium = 0;
        int low = 0;
        for (JsonNode c : contacts) {
            Integer inactiveDays = null;
            if (c.has("inactive_days") && !c.path("inactive_days").isNull()) {
                inactiveDays = c.path("inactive_days").asInt(-1);
                if (inactiveDays < 0) {
                    inactiveDays = null;
                }
            }
            ObjectNode one = assessOne(inactiveDays, threshold);
            one.put("contact_id", c.path("contact_id").asLong());
            results.add(one);
            switch (one.path("tier").asText()) {
                case "HIGH" -> high++;
                case "MEDIUM" -> medium++;
                default -> low++;
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.set("results", results);
        ObjectNode summary = out.putObject("summary");
        summary.put("scanned", results.size());
        summary.put("HIGH", high);
        summary.put("MEDIUM", medium);
        summary.put("LOW", low);
        return out;
    }

    private static JsonNode assessOne(JsonNode input, Integer thresholdOverride) {
        int threshold = thresholdOverride != null ? thresholdOverride
                : input == null || input.path("threshold_days").isMissingNode()
                ? DEFAULT_THRESHOLD : input.path("threshold_days").asInt(DEFAULT_THRESHOLD);
        Integer inactiveDays = null;
        if (input != null && input.has("inactive_days") && !input.path("inactive_days").isNull()) {
            inactiveDays = input.path("inactive_days").asInt(-1);
            if (inactiveDays < 0) {
                inactiveDays = null;
            }
        }
        return assessOne(inactiveDays, threshold);
    }

    private static ObjectNode assessOne(Integer inactiveDays, int threshold) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode drivers = out.putArray("drivers");
        if (inactiveDays == null) {
            out.put("churn_risk", 90);
            out.put("tier", "HIGH");
            drivers.add("从未活跃");
        } else if (inactiveDays > threshold) {
            out.put("churn_risk", 75);
            out.put("tier", "HIGH");
            drivers.add(inactiveDays + "天未活跃");
        } else {
            out.put("churn_risk", 5);
            out.put("tier", "LOW");
        }
        return out;
    }
}