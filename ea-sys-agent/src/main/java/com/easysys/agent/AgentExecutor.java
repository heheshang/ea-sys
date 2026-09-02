package com.easysys.agent;

import com.easysys.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 智能体执行器（AgentScope Java 2.0 承载）：主提供方以确定性 RuleModel 身份经 ReActAgent
 * 执行（框架 native 结构化输出解析 + 多租户 RuntimeContext session）→ 结构化输出
 * networknt schema 硬校验 → 置信度阈值闸门 → 任一环节失败落入确定性 fallback。
 * 审计载荷随结果返回，由调用方持久化（audit_log）。
 *
 * <p>兜底语义（docs/04-agent-design.md）：LLM 不可达 / 输出非法 / 置信度不足 →
 * 租户配置的默认分层（通道优先）直接生效，执行不中断。确定性提供方（本里程碑）
 * 天然通过全部闸门，LLM 接入时硬校验与降级即刻生效。</p>
 *
 * <p>框架边界说明（M6 实测结论）：AgentScope native 结构化路径只做 JSON 解析，不做
 * schema 语义校验（enum/required/minItems 违规照单全收）——因此 schema 硬校验保留在
 * 本执行器（networknt 2.0.0）；ExecutionConfig 超时/重试对自定义 Model 不生效
 * （doStream 抛错直接传播），主提供方调用仍由本执行器自建 CALL_POOL 承担
 * 超时 + 重试（幂等假设），与 AgentRunConfig(retries/timeoutMs) 语义一致。</p>
 */
public final class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** LLM 主提供方配置：从 application.yml 的 easysys.agent.llm 段注入（apiKey 经环境变量占位符，不入库）。 */
    private static volatile AgentLlmConfig llmConfig = AgentLlmConfig.disabled();

    /** Spring 侧 yml 绑定完成后调用；测试也可直接注入。传 null 恢复确定性默认。 */
    public static void configureLlm(AgentLlmConfig config) {
        llmConfig = config == null ? AgentLlmConfig.disabled() : config;
        log.info("LLM 主提供方配置: enabled={} model={}", llmConfig.enabled(), llmConfig.modelId());
    }

    /** networknt 2.0.0：SchemaRegistry 编译（1.5.5 的 JsonSchemaFactory API 已移除）。 */
    private static final SchemaRegistry SCHEMA_REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
    private static final ConcurrentHashMap<String, Schema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    private static final ExecutorService CALL_POOL = Executors.newCachedThreadPool(daemonFactory("agent-call-"));

    private AgentExecutor() {
    }

    /**
     * 执行一次智能体调用。
     *
     * @param agent    主提供方（逻辑包成框架确定性 Model 执行）
     * @param fallback 确定性兜底（不可失败）
     * @param action   动作标识（写入审计，如 strategy_generate / layer_tag / route_split）
     * @param input    结构化入参（审计摘要同源；同时作为框架模型输入）
     * @param cfg      阈值 / 重试 / 超时
     */
    public static AgentOutcome run(StrategyAgent agent, AgentFallback fallback, String action,
                                   JsonNode input, AgentRunConfig cfg) {
        long start = System.currentTimeMillis();
        String status = "SUCCESS";
        String reason = null;
        JsonNode candidate = null;

        try {
            candidate = tryPlan(agent, action, input, cfg);
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

    /**
     * 主提供方调用：确定性规则包成框架 Model 经 ReActAgent 执行，超时 + 失败重试（幂等假设）。
     * 框架 ExecutionConfig 对自定义 Model 不重试（实测），故重试循环留在本执行器。
     */
    private static JsonNode tryPlan(StrategyAgent agent, String action, JsonNode input, AgentRunConfig cfg) throws Exception {
        Exception last = null;
        // LLM 调用昂贵且外部故障通常立即可见（认证/网络），重试一次即降级，保证执行不中断的时效。
        int retries = llmConfig.active() ? Math.min(cfg.retries(), 1) : cfg.retries();
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return callAgent(agent, action, input, cfg);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }, CALL_POOL);
                try {
                    return future.get(effectiveTimeoutMs(cfg), TimeUnit.MILLISECONDS);
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

    /**
     * 单次框架调用：StrategyAgent.plan 包成确定性 Model，经 ReActAgent 结构化输出管道执行，
     * 输出 JSON 由框架 native 路径解析，多租户隔离由 RuntimeContext(userId=tenantId, sessionId=action) 承载。
     */
    private static JsonNode callAgent(StrategyAgent agent, String action, JsonNode input, AgentRunConfig cfg) throws Exception {
        Model model = primaryModel(agent);
        ReActAgent reAct = ReActAgent.builder()
                .name(action)
                .sysPrompt(sysPrompt(agent.type()))
                .model(model)
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofMillis(effectiveTimeoutMs(cfg)))
                        .maxAttempts(cfg.retries() + 1)
                        .build())
                .build();
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(tenantIdOr("anonymous"))
                .sessionId(action)
                .build();
        JsonNode schema = MAPPER.readTree(agent.schema());
        Msg result = reAct.call(List.of(new UserMessage(input.toString())), schema, ctx)
                .block(Duration.ofMillis(effectiveTimeoutMs(cfg)));
        return MAPPER.readTree(result.getTextContent());
    }

    /**
     * 主提供方模型：LLM 已启用（yml 注入 apiKey）时经 ModelRegistry 解析 OpenAI 兼容模型
     * （openai:qwen3.7-plus → Token Plan 端点），否则维持确定性 RuleModel —— 降级链路零改动即刻生效。
     *
     * <p>resolve 失败（如 apiKey 缺失/非法、provider 未注册）抛出异常，由 run 捕获为
     * provider_error 落入确定性 fallback —— 「LLM 全挂仍可运营」的最后一道闸。</p>
     */
    private static Model primaryModel(StrategyAgent agent) {
        AgentLlmConfig cfg = llmConfig;
        if (cfg.active()) {
            return ModelRegistry.resolve(cfg.modelId(), ModelCreationContext.builder()
                    .apiKey(cfg.apiKey())
                    .baseUrl(cfg.baseUrl())
                    .build());
        }
        return new RuleModel("deterministic", agent::plan);
    }

    /** LLM 模式超时取配置值（qwen3.7-plus 为 reasoning 模型，响应 ~20s），确定性模式沿用调用方 cfg。 */
    private static long effectiveTimeoutMs(AgentRunConfig cfg) {
        return llmConfig.active() ? llmConfig.timeoutMs() : cfg.timeoutMs();
    }

    private static String tenantIdOr(String fallback) {
        Long tid = TenantContext.tenantId();
        return tid == null ? fallback : String.valueOf(tid);
    }

    private static String sysPrompt(AgentType type) {
        return switch (type) {
            case LAYER -> "你是运营分层策略规划智能体：根据入参输出多通道触达分层策略 JSON";
            case ROUTER -> "你是触达路由决策智能体：根据入参输出单用户通道路由决策 JSON";
            case CHURN -> "你是流失风险评测智能体：根据入参输出成员流失风险批量评估 JSON";
            case WORKFLOW -> "你是运营工作流设计智能体：根据自然语言需求与租户模板/人群/通道上下文，输出工作流 DAG JSON";
        };
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

    private static String agentTypeModel(StrategyAgent agent) {
        return llmConfig.active() ? llmConfig.modelId() : "deterministic";
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + System.nanoTime());
            t.setDaemon(true);
            return t;
        };
    }
}