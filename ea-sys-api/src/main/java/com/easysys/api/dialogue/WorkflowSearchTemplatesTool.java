package com.easysys.api.dialogue;

import com.easysys.api.service.AiWorkflowService;
import com.easysys.api.dto.template.TemplateView;
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
 * 对话工具：search_templates —— 全租户共享模板列表（快照形状同生成内
 * {@code [{id, channel, name, content, status}]}，模板内容供策略器 bestTemplate 匹配）。
 * 注意模板 content 可大：与 generateForTenant 快照语义一致，原样输出。
 */
@Component
public class WorkflowSearchTemplatesTool extends WorkflowToolBase {

    private final AiWorkflowService aiWorkflowService;
    private final ObjectMapper json;

    public WorkflowSearchTemplatesTool(AiWorkflowService aiWorkflowService, ObjectMapper json) {
        super(ToolBase.builder()
                .name("search_templates")
                .description("查询可用的发送模板（含模板正文内容，用于需求匹配）")
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
            ArrayNode arr = json.createArrayNode();
            for (TemplateView t : aiWorkflowService.searchTemplatesFor()) {
                arr.addObject()
                        .put("id", t.id() == null ? 0 : t.id())
                        .put("channel", String.valueOf(t.channel()))
                        .put("name", String.valueOf(t.name()))
                        .put("content", String.valueOf(t.content()))
                        .put("status", t.status() == null ? "" : String.valueOf(t.status()));
            }
            String text = arr.isEmpty()
                    ? json.createObjectNode().put("note", "暂无可用模板").toString()
                    : arr.toString();
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, "search_templates", TextBlock.builder().text(text).build());
        })).onErrorResume(e -> errorResult(param, "search_templates", json, e));
    }
}