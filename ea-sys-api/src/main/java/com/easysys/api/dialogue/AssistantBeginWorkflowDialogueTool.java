package com.easysys.api.dialogue;

import com.easysys.agent.AssistantPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 助手工具：begin_workflow_dialogue —— 模式切换到「工作流创建助手」的标记工具。
 * 执行后无副作用；控制器监听其 ToolResultEndEvent(SUCCESS) 向前端发射
 * {@code switch_workflow_dialogue} 卡片事件，前端据此切换会话（复用 /api/workflows/ai-chat）。
 */
@Component
public class AssistantBeginWorkflowDialogueTool extends AssistantToolBase {

    private final ObjectMapper json;

    public AssistantBeginWorkflowDialogueTool(ObjectMapper json) {
        super(ToolBase.builder()
                .name(AssistantPolicy.TOOL_BEGIN_WORKFLOW_DIALOGUE)
                .description("用户想创建/设计运营工作流时调用：切换到工作流创建助手（复用对话式创建工作流会话）")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .readOnly(true)
                .concurrencySafe(true));
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.allow("只读切换"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String id = param.getToolUseBlock().getId();
        return Mono.just(new ToolResultBlock(id, AssistantPolicy.TOOL_BEGIN_WORKFLOW_DIALOGUE,
                TextBlock.builder().text(json.createObjectNode()
                        .put("note", "已切换到工作流创建助手").toString()).build()));
    }
}