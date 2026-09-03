package com.easysys.api.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * AI 智能客服工具公共基类：与 {@link WorkflowToolBase} 同构，
 * 租户/失败处理统一委托 {@link ToolSupport}（工具线程无请求租户 ThreadLocal）。
 */
abstract class AssistantToolBase extends ToolBase {

    protected AssistantToolBase(Builder builder) {
        super(builder);
    }

    protected static Long requiredTenant(ToolCallParam param) {
        return ToolSupport.requiredTenant(param);
    }

    protected static <T> Mono<T> withTenant(Long tenantId, Supplier<Mono<T>> action) {
        return ToolSupport.withTenant(tenantId, action);
    }

    protected static Mono<ToolResultBlock> errorResult(ToolCallParam param, String name, ObjectMapper json, Throwable e) {
        return ToolSupport.errorResult(param, name, json, e);
    }
}