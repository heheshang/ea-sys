package com.easysys.api.dialogue;

import com.easysys.api.service.AiWorkflowService;
import com.easysys.api.dto.audience.AudienceResponse;
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
 * 对话工具：search_audiences —— 当前租户人群列表（形状 {@code [{id, name, rule}]}，
 * 策略器追问时引用现有人群名 / 判定人群要素是否已表达）。
 */
@Component
public class WorkflowSearchAudiencesTool extends WorkflowToolBase {

    private final AiWorkflowService aiWorkflowService;
    private final ObjectMapper json;

    public WorkflowSearchAudiencesTool(AiWorkflowService aiWorkflowService, ObjectMapper json) {
        super(ToolBase.builder()
                .name("search_audiences")
                .description("查询当前租户已保存的人群（含圈选规则，用于匹配目标人群）")
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
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Long tenantId = requiredTenant(param);
            ArrayNode arr = json.createArrayNode();
            for (AudienceResponse a : aiWorkflowService.searchAudiencesFor(tenantId)) {
                arr.addObject()
                        .put("id", a.id() == null ? 0 : a.id())
                        .put("name", String.valueOf(a.name()))
                        .put("rule", a.rule() == null ? "" : String.valueOf(a.rule()));
            }
            String text = arr.isEmpty()
                    ? json.createObjectNode().put("note", "当前租户暂无人群").toString()
                    : arr.toString();
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, "search_audiences", TextBlock.builder().text(text).build());
        })).onErrorResume(e -> errorResult(param, "search_audiences", json, e));
    }
}