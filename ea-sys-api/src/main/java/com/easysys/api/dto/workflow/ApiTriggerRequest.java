package com.easysys.api.dto.workflow;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * API 触发请求：外部系统（CRM/商城）按 workflowId 携带单用户维度数据入流。
 * 载荷置于事件映射（event.*），条件节点可按其路由。
 */
public record ApiTriggerRequest(
        @NotNull Long contactId,
        Map<String, Object> payload) {
}