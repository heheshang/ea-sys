package com.easysys.api.dialogue;

import com.easysys.api.service.AiWorkflowService;
import com.easysys.api.dto.channel.ChannelConfigView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 对话工具：list_channels —— 查询当前租户可用触达通道（回收 AiWorkflowService 查询能力，
 * 输出形状与生成内 summary 一致 {@code [{channel, enabled}]}，供策略器画像判定）。
 */
@Component
public class WorkflowListChannelsTool extends WorkflowToolBase {

    private final AiWorkflowService aiWorkflowService;
    private final ObjectMapper json;

    public WorkflowListChannelsTool(AiWorkflowService aiWorkflowService, ObjectMapper json) {
        super(ToolBase.builder()
                .name("list_channels")
                .description("查询当前租户可用触达通道（按启用状态）")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .readOnly(true)
                .concurrencySafe(true));
        this.aiWorkflowService = aiWorkflowService;
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.allow("只读查询"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 工具线程无 TenantContext：注入租户后再查（查询走 MyBatis 租户拦截器）
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Long tenantId = requiredTenant(param);
            ArrayNode arr = json.createArrayNode();
            for (ChannelConfigView v : aiWorkflowService.listChannelsFor(tenantId)) {
                arr.addObject()
                        .put("channel", v.channel())
                        .put("enabled", Boolean.TRUE.equals(v.enabled()));
            }
            String text = arr.isEmpty()
                    ? json.createObjectNode().put("note", "租户无通道配置").toString()
                    : arr.toString();
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, "list_channels", TextBlock.builder().text(text).build());
        })).onErrorResume(e -> errorResult(param, "list_channels", json, e));
    }
}