package com.easysys.agent;

import com.easysys.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批处理智能体治理策略（AgentScope Java 2.0 承载）：执行由调用方注入的 {@link HarnessAgent}
 * 单例 —— 模型位为确定性 RuleModel（或 LLM 主提供方），同步 call 驱动 —— 随后按
 * 架构降级契约（docs/04-agent-design.md）做：结构化输出 networknt schema 硬校验 →
 * 置信度阈值闸门 → 任一环节失败落入确定性 fallback。审计载荷随结果返回，由调用方持久化（audit_log）。
 *
 * <p>与已退役的 {@code AgentExecutor} 相比，本类只保留「合规编排」：超时/重试/调用管道由
 * HarnessAgent 框架承载（会话无状态：disableSessionPersistence + RuntimeContext 多租户隔离），
 * 自建线程池与临时 ReActAgent 构造移除 —— 三路（LAYER/CHURN/WORKFLOW）统一走同一框架执行面。</p>
 *
 * <p>框架边界说明（M6 实测结论，AgentPolicy 继承同一定案）：AgentScope native 结构化路径只做
 * JSON 解析、不做 schema 语义校验（enum/required/minItems 违规照单全收）——因此 schema 硬校验
 * 保留在本策略（networknt 2.0.0）；确定性提供方（本里程碑）天然通过全部闸门，LLM 接入时
 * 硬校验与降级即刻生效。</p>
 */
public final class AgentPolicy {

    private static final Logger log = LoggerFactory.getLogger(AgentPolicy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** networknt 2.0.0：SchemaRegistry 编译（1.5.5 的 JsonSchemaFactory API 已移除）。 */
    private static final SchemaRegistry SCHEMA_REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
    private static final ConcurrentHashMap<String, Schema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    private AgentPolicy() {
    }

    /**
     * 执行一次批处理智能体调用（无状态、幂等）。
     *
     * @param agent    HarnessAgent 单例（模型位 = 确定性 RuleModel 或 LLM 主提供方）
     * @param planner  主提供方计划器（schema()/type() 供校验与审计；模型位内部同源规则）
     * @param fallback 确定性兜底（不可失败）
     * @param action   动作标识（写入审计，如 strategy_generate / churn_scan / workflow_generate）
     * @param input    结构化入参（审计摘要同源；同时作为框架模型输入）
     * @param cfg      阈值 / 超时
     */
    public static AgentOutcome run(HarnessAgent agent, StrategyAgent planner, AgentFallback fallback,
                                   String action, JsonNode input, AgentRunConfig cfg) {
        long start = System.currentTimeMillis();
        String status = "SUCCESS";
        String reason = null;
        JsonNode candidate = null;

        try {
            candidate = call(agent, action, input, cfg);
            if (!matches(planner.schema(), candidate)) {
                candidate = null;
                status = "FALLBACK";
                reason = "schema_invalid";
            } else if (confidenceOf(candidate) < cfg.confidenceThreshold()) {
                candidate = null;
                status = "FALLBACK";
                reason = "low_confidence";
            }
        } catch (Exception e) {
            candidate = null;
            status = "FALLBACK";
            reason = "provider_error:" + e.getClass().getSimpleName();
        }

        if (candidate == null) {
            try {
                candidate = fallback.fallback(input);
                if (!matches(planner.schema(), candidate)) {
                    status = "ERROR";
                    reason = "fallback_invalid";
                }
            } catch (Exception e) {
                status = "ERROR";
                reason = "fallback_error:" + e.getClass().getSimpleName();
            }
        }

        long durationMs = System.currentTimeMillis() - start;
        double confidence = candidate == null ? 0 : confidenceOf(candidate);
        log.info("agent {} {} status={} reason={} confidence={} cost={}ms",
                planner.type(), action, status, reason, confidence, durationMs);

        AgentCall audit = new AgentCall(planner.type(), action, status, reason, input, candidate,
                candidate == null ? null : candidate.path("strategy_version").asText(null),
                candidate == null ? null : (candidate.path("confidence").isNumber() ? candidate.path("confidence").asDouble() : null),
                agentModel(agent), null, durationMs);
        return new AgentOutcome(status, reason, candidate, audit);
    }

    /**
     * 主提供方调用：HarnessAgent 同步执行（模型位产 JSON 文本，框架 native 结构化路径解析）。
     * 超时由 cfg.timeoutMs 约束（确定性即时返回；LLM 接入时调用方传入对应超时）。
     */
    private static JsonNode call(HarnessAgent agent, String action, JsonNode input, AgentRunConfig cfg) throws Exception {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(tenantIdOr("anonymous"))
                .sessionId(action)
                .build();
        Msg result = agent.call(List.of(new UserMessage(input.toString())), ctx)
                .block(Duration.ofMillis(cfg.timeoutMs()));
        if (result == null) {
            throw new IllegalStateException("agent call returned null");
        }
        String text = result.getTextContent();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("agent call returned empty content");
        }
        return MAPPER.readTree(text);
    }

    private static String agentModel(HarnessAgent agent) {
        try {
            return agent.getModel().getModelName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String tenantIdOr(String fallback) {
        Long tid = TenantContext.tenantId();
        return tid == null ? fallback : String.valueOf(tid);
    }

    private static double confidenceOf(JsonNode output) {
        JsonNode c = output == null ? null : output.get("confidence");
        return c != null && c.isNumber() ? c.asDouble() : 1.0;
    }

    /** networknt 2.0.0 语义校验：Schema.validate 返回 Error 列表，空即符合（框架 native 路径不做语义校验）。 */
    private static boolean matches(String schema, JsonNode doc) {
        if (schema == null || schema.isBlank()) {
            return true;
        }
        try {
            Schema compiled = SCHEMA_CACHE.computeIfAbsent(schema, s -> SCHEMA_REGISTRY.getSchema(s, InputFormat.JSON));
            return compiled.validate(doc).isEmpty();
        } catch (Exception e) {
            log.warn("schema 编译失败，跳过结构校验: {}", e.getMessage());
            return true;
        }
    }
}