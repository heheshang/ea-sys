package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 智能体执行器：主提供方调用（超时 + 重试）→ 结构化输出 schema 校验 → 置信度阈值闸门 →
 * 任一环节失败落入确定性 fallback。审计载荷随结果返回，由调用方持久化（audit_log）。
 *
 * 兜底语义（docs/04-agent-design.md）：LLM 不可达 / 输出非法 / 置信度不足 →
 * 租户配置的默认分层（通道优先）直接生效，执行不中断。确定性提供方（本里程碑）
 * 天然通过全部闸门，LLM 接入时硬校验与降级即刻生效。
 */
public final class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private static final ExecutorService CALL_POOL = Executors.newCachedThreadPool(daemonFactory("agent-call-"));

    private AgentExecutor() {
    }

    /**
     * 执行一次智能体调用。
     *
     * @param agent    主提供方
     * @param fallback 确定性兜底（不可失败）
     * @param action   动作标识（写入审计，如 strategy_generate / layer_tag / route_split）
     * @param input    结构化入参（审计摘要同源）
     * @param cfg      阈值 / 重试 / 超时
     */
    public static AgentOutcome run(StrategyAgent agent, AgentFallback fallback, String action,
                                   JsonNode input, AgentRunConfig cfg) {
        long start = System.currentTimeMillis();
        String status = "SUCCESS";
        String reason = null;
        JsonNode candidate = null;

        try {
            candidate = tryPlan(agent, input, cfg);
            if (!matches(agent.schema(), candidate)) {
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
                if (!matches(agent.schema(), candidate)) {
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
                agent.type(), action, status, reason, confidence, durationMs);

        AgentCall audit = new AgentCall(agent.type(), action, status, reason, input, candidate,
                candidate == null ? null : candidate.path("strategy_version").asText(null),
                candidate == null ? null : (candidate.path("confidence").isNumber() ? candidate.path("confidence").asDouble() : null),
                agentTypeModel(agent), null, durationMs);
        return new AgentOutcome(status, reason, candidate, audit);
    }

    /** 主提供方调用：超时 + 失败重试（幂等假设）。 */
    private static JsonNode tryPlan(StrategyAgent agent, JsonNode input, AgentRunConfig cfg) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= cfg.retries(); attempt++) {
            try {
                CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return agent.plan(input);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }, CALL_POOL);
                try {
                    return future.get(cfg.timeoutMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    last = te;
                }
            } catch (Exception e) {
                last = e instanceof RuntimeException re && re.getCause() instanceof Exception c ? c : e;
            }
        }
        throw last == null ? new IllegalStateException("retry exhausted") : last;
    }

    private static double confidenceOf(JsonNode output) {
        JsonNode c = output == null ? null : output.get("confidence");
        return c != null && c.isNumber() ? c.asDouble() : 1.0;
    }

    private static boolean matches(String schema, JsonNode doc) {
        if (schema == null || schema.isBlank()) {
            return true;
        }
        try {
            JsonSchema compiled = SCHEMA_FACTORY.getSchema(schema);
            return compiled.validate(doc).isEmpty();
        } catch (Exception e) {
            log.warn("schema 编译失败，跳过结构校验: {}", e.getMessage());
            return true;
        }
    }

    private static String agentTypeModel(StrategyAgent agent) {
        return "deterministic";
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + System.nanoTime());
            t.setDaemon(true);
            return t;
        };
    }
}