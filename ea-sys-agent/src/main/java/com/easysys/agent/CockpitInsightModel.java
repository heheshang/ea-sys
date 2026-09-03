package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 确定性驾驶舱洞察规划器（规则主实现/兜底）：汇总 LLM 调用监控（audit_log 聚合）
 * 与图谱/知识库/记忆状态，按阈值规则产出洞察列表与健康分。
 * 输出自洽满足 {@link LayerSchemas#cockpitInsightSchema()}：insights 无条件至少一条
 * （无异常也产「运行正常」info 项，minItems=1）。
 *
 * 入参（CockpitService 组装 digest）：
 * <pre>
 * {"llm_enabled": false, "model_id": "...",
 *  "llm": {"total_calls": N, "success": N, "fallback": N, "error": N,
 *          "avg_duration_ms": x, "total_tokens": N, "total_cost": x,
 *          "schema_valid_rate": x, "error_rate": x, "fallback_rate": x,
 *          "trend": [{"day":"2026-09-01","calls":N,"tokens":N,"cost":x}, ...]},
 *  "graph": {"total": N, "enabled": N},
 *  "knowledge": {"docs": N, "chunks": N},
 *  "memory_keys": N,
 *  "evaluation": {"datasets": N, "reports": N}}
 * </pre>
 * 健康分 = 100 - 加权扣分（失败率/降级率/schema 率/趋势恶化/知识库异常），clamp 0-100；
 * LLM-agnostic：未启用 LLM 时 tokens/cost 恒 0，洞察仍按调用可靠性产出。
 */
public final class CockpitInsightModel implements StrategyAgent, AgentFallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public AgentType type() {
        return AgentType.COCKPIT;
    }

    @Override
    public String schema() {
        return LayerSchemas.cockpitInsightSchema();
    }

    @Override
    public JsonNode plan(JsonNode input) {
        return build(input);
    }

    @Override
    public JsonNode fallback(JsonNode input) {
        return build(input);
    }

    private static JsonNode build(JsonNode input) {
        ObjectNode llm = input == null || !input.path("llm").isObject()
                ? MAPPER.createObjectNode() : (ObjectNode) input.path("llm");
        int totalCalls = llm.path("total_calls").asInt(0);
        double errorRate = llm.path("error_rate").asDouble(0);
        double fallbackRate = llm.path("fallback_rate").asDouble(0);
        double schemaValidRate = llm.path("schema_valid_rate").isMissingNode()
                ? (totalCalls == 0 ? 1.0 : llm.path("schema_valid_rate").asDouble(1.0))
                : llm.path("schema_valid_rate").asDouble(1.0);
        boolean llmEnabled = input != null && input.path("llm_enabled").asBoolean(false);
        String modelId = input == null ? "" : input.path("model_id").asText("");

        ObjectNode graph = input == null || !input.path("graph").isObject()
                ? MAPPER.createObjectNode() : (ObjectNode) input.path("graph");
        int graphTotal = graph.path("total").asInt(0);
        ObjectNode knowledge = input == null || !input.path("knowledge").isObject()
                ? MAPPER.createObjectNode() : (ObjectNode) input.path("knowledge");
        int kbDocs = knowledge.path("docs").asInt(0);
        int kbChunks = knowledge.path("chunks").asInt(0);

        ArrayNode insights = MAPPER.createArrayNode();
        int penalty = 0;

        if (totalCalls > 0 && errorRate >= 0.05) {
            penalty += 30;
            insights.add(insight("anomaly", "critical", "LLM 调用失败率偏高",
                    "近 N 天调用失败率 " + pct(errorRate) + "（阈值 5%）",
                    "llm.error_rate", "检查模型端点/认证配置，必要时提高 AgentPolicy 超时并观察 provider_error 原因"));
        }
        if (totalCalls > 0 && fallbackRate >= 0.1) {
            penalty += 20;
            insights.add(insight("anomaly", "warning", "确定性降级（fallback）占比偏高",
                    "近 N 天 " + pct(fallbackRate) + " 的调用落入确定性兜底（schema 不符/低置信度/提供方异常）",
                    "llm.fallback_rate", "复核 schema 与提示词一致性；LLM 路径排查 provider_error 原因"));
        }
        if (totalCalls > 0 && schemaValidRate < 0.9) {
            penalty += 15;
            insights.add(insight("anomaly", "warning", "结构化输出 schema 通过率低",
                    "schema 硬校验通过率 " + pct(schemaValidRate) + "（阈值 90%）",
                    "llm.schema_valid_rate", "检查输出 schema 与 sysPrompt 约束是否一致"));
        }
        if (!llmEnabled) {
            insights.add(insight("info", "info", "LLM 主提供方未启用",
                    "当前为确定性规则模式（RuleModel），调用可观测、token/成本为 0；"
                            + (modelId.isBlank() ? "" : "配置模型 " + modelId + "。")
                            + " 开启 easysys.agent.llm 后驾驶舱将展示真实 token 消耗与成本。",
                    "llm.enabled", "如需真实 LLM 评测与 token 监控，配置 EA_LLM_API_KEY 并置 enabled=true"));
        }
        if (trendDegraded(llm)) {
            penalty += 15;
            insights.add(insight("trend", "warning", "LLM 调用量/耗时呈上升趋势",
                    "近两日调用均值较前期均值上升超过 1.5 倍，关注容量与成本",
                    "llm.trend", "核查触发频率与批处理窗口，必要时限流或拆分批次"));
        }
        if (kbDocs > 0 && kbChunks == 0) {
            penalty += 10;
            insights.add(insight("anomaly", "warning", "知识库分块缺失",
                    "已登记 " + kbDocs + " 篇文档但分块数为 0，检索工具将无召回",
                    "knowledge.chunks", "重新执行文档分块/索引任务"));
        }
        if (insights.isEmpty()) {
            insights.add(insight("info", "info", "运行正常",
                    "LLM 调用与图谱/知识库/记忆各项指标均在阈值内，无异常发现（图谱登记 "
                            + graphTotal + " 项）", null, null));
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("generated_at", java.time.Instant.now().toString());
        out.put("overall_health", Math.max(0, Math.min(100, 100 - penalty)));
        out.set("insights", insights);
        out.put("strategy_version", "rule");
        out.put("confidence", 1.0);
        return out;
    }

    /** 趋势恶化：trend 至少 5 个数据点且后 2 日调用均值 > 前段均值 * 1.5。 */
    private static boolean trendDegraded(JsonNode llm) {
        JsonNode trend = llm.path("trend");
        if (!trend.isArray() || trend.size() < 5) {
            return false;
        }
        int n = trend.size();
        double recent = 0;
        for (int i = n - 2; i < n; i++) {
            recent += trend.get(i).path("calls").asInt(0);
        }
        recent /= 2;
        double prior = 0;
        for (int i = 0; i < n - 2; i++) {
            prior += trend.get(i).path("calls").asInt(0);
        }
        prior /= (n - 2);
        return recent > prior * 1.5;
    }

    private static ObjectNode insight(String type, String severity, String title, String detail,
                                      String metricRef, String suggestion) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("type", type);
        o.put("severity", severity);
        o.put("title", title);
        o.put("detail", detail);
        if (metricRef != null) {
            o.put("metric_ref", metricRef);
        }
        if (suggestion != null) {
            o.put("suggestion", suggestion);
        }
        return o;
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }
}