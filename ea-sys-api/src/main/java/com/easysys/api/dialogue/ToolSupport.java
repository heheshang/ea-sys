package com.easysys.api.dialogue;

import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * 对话工具公共支撑（工作流对话与 AI 智能客服两套工具共用，避免第二套约定）：
 * 工具在线程池执行、无请求租户 ThreadLocal，统一从 RuntimeContext 取租户并把租户注入
 * TenantContext 执行查询，失败统一产出 ERROR 结果块（文案进结果文本，由框架标记状态）。
 */
public final class ToolSupport {

    private ToolSupport() {
    }

    public static Long requiredTenant(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        Long tenantId = rc.get("tenantId", Long.class);
        if (tenantId == null) {
            throw new IllegalStateException("缺少 tenantId 运行时上下文，无法执行查询");
        }
        return tenantId;
    }

    /** 工具在线程池执行且无请求租户 ThreadLocal：订阅时注入租户，链结束后清理（含异常路径）。 */
    public static <T> Mono<T> withTenant(Long tenantId, Supplier<Mono<T>> action) {
        return Mono.defer(() -> {
            TenantContext.set(new TenantInfo(tenantId));
            try {
                return action.get().doFinally(s -> TenantContext.clear());
            } catch (RuntimeException e) {
                TenantContext.clear();
                throw e;
            }
        });
    }

    public static Mono<ToolResultBlock> errorResult(ToolCallParam param, String name, ObjectMapper json, Throwable e) {
        return Mono.just(new ToolResultBlock(param.getToolUseBlock().getId(), name,
                TextBlock.builder().text(serializeError(json, e)).build())
                .withState(ToolResultState.ERROR));
    }

    private static String serializeError(ObjectMapper json, Throwable e) {
        // 工具失败不重复兜底：文案进结果文本（策略器/前端可见），由框架标记 ERROR
        return json.createObjectNode()
                .put("error", e.getClass().getSimpleName())
                .put("message", e.getMessage() == null ? "" : e.getMessage())
                .toString();
    }
}