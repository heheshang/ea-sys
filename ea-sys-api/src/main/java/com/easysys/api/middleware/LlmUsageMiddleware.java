package com.easysys.api.middleware;

import com.easysys.api.service.LlmUsageService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * LLM 用量采集中间件（驾驶舱 LLM 卡数据源之一）。
 *
 * <p>采集点 = {@code onModelCall}：pre 阶段拿到模型原始输入（messages + tools）估算六类
 * 上下文构成；下游事件流拦截 {@link ModelCallEndEvent}（含 {@link ChatUsage}）拿到真实
 * token 用量 —— 输入构成与 usage 同一调用闭环，一次 onModelCall 记一次。
 *
 * <p>只记真实 LLM 调用（usage &gt; 0）：确定性模型（RuleModel/AssistantModel/
 * WorkflowDialogueModel，usage 0/0）不写行 —— 测试与确定性模式零影响。
 *
 * <p>构成估算见 {@link LlmContextEstimator}（中间件与驾驶舱查询期 AgentState 派生共用，
 * 口径统一）。</p>
 */
@Component
public class LlmUsageMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageMiddleware.class);

    private final LlmUsageService llmUsageService;

    public LlmUsageMiddleware(LlmUsageService llmUsageService) {
        this.llmUsageService = llmUsageService;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        String contextJson = LlmContextEstimator.compose(input.messages(), input.tools());
        AtomicReference<ChatUsage> usageRef = new AtomicReference<>();
        return next.apply(input)
                .doOnNext(ev -> {
                    if (ev instanceof ModelCallEndEvent end && end.getUsage() != null) {
                        usageRef.set(end.getUsage());
                    }
                })
                .doOnComplete(() -> {
                    ChatUsage usage = usageRef.get();
                    if (usage == null || usage.getTotalTokens() <= 0) {
                        return; // 确定性模型无真实使用量，不记
                    }
                    Long tenantId = resolveTenant(ctx);
                    if (tenantId == null) {
                        return;
                    }
                    String agentType = agent.getName() != null ? agent.getName() : "unknown";
                    llmUsageService.recordCall(tenantId, agentType, ctx.getSessionId(),
                            usage.getInputTokens(), usage.getOutputTokens(), usage.getCachedTokens(),
                            contextJson);
                });
    }

    /** 租户：优先 RuntimeContext 显式 tenantId，回退 userId（批处理 userId=tenantId 字符串）。 */
    private Long resolveTenant(RuntimeContext ctx) {
        try {
            Long typed = ctx.get("tenantId", Long.class);
            if (typed != null) {
                return typed;
            }
        } catch (Exception ignored) {
            // 类型不符时回退 userId 解析
        }
        String userId = ctx.getUserId();
        if (userId != null && !userId.isBlank() && !"anonymous".equals(userId)) {
            try {
                return Long.parseLong(userId);
            } catch (NumberFormatException ignored) {
                // 非租户 id，跳过
            }
        }
        return null;
    }

    // ---------- 上下文构成估算见 LlmContextEstimator（中间件与驾驶舱共用） ----------
}