package com.easysys.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话式创建工作流：确定性模型位（同 RuleModel 模式）。
 *
 * <p>不依赖任何 LLM：读全量会话历史 → {@link WorkflowDialoguePolicy#decide} 决策 →
 * 组装一个 ChatResponse（多个 TextBlock 逐句分片 + 末尾 ToolUseBlock 工具意图）。
 * 文本块被框架逐块 emit TextBlockDeltaEvent → 前端打字机；工具块进入 ReAct 循环，
 * plan_workflow 经工具 checkPermissions(ASK) 走 HITL 人工确认闸门。
 *
 * <p>接入真实 LLM（如 ultrathink）时：框架同一模型位换 ModelRegistry 解析出的模型，
 * 对话流程/HITL 语义零改动 —— 工具与 HITL 均为框架级能力。
 */
public class WorkflowDialogueModel extends ChatModelBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WorkflowDialogueModel() {
        // 对话需自由文本 + 工具调用混合输出，不使用 native 结构化输出
    }

    @Override
    public String getModelName() {
        return "workflow-dialogue";
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
        if (msgs == null || msgs.isEmpty()) {
            return Flux.error(new IllegalStateException("对话历史为空"));
        }
        try {
            WorkflowDialoguePolicy.Action action = WorkflowDialoguePolicy.decide(msgs);
            return Flux.just(ChatResponse.builder()
                    .content(buildBlocks(action))
                    .usage(ChatUsage.builder().inputTokens(0).outputTokens(0).build())
                    .finishReason("stop")
                    .build());
        } catch (Exception e) {
            return Flux.error(e);
        }
    }

    private static List<ContentBlock> buildBlocks(WorkflowDialoguePolicy.Action action) {
        List<ContentBlock> blocks = new ArrayList<>();
        switch (action) {
            case WorkflowDialoguePolicy.Reply r -> {
                for (String chunk : r.chunks()) {
                    blocks.add(TextBlock.builder().text(chunk).build());
                }
            }
            case WorkflowDialoguePolicy.Query q -> {
                for (String chunk : q.prefaceChunks()) {
                    blocks.add(TextBlock.builder().text(chunk).build());
                }
                blocks.add(toolCall(WorkflowDialoguePolicy.TOOL_LIST_CHANNELS, Map.of()));
                blocks.add(toolCall(WorkflowDialoguePolicy.TOOL_SEARCH_TEMPLATES, Map.of()));
                blocks.add(toolCall(WorkflowDialoguePolicy.TOOL_SEARCH_AUDIENCES, Map.of()));
            }
            case WorkflowDialoguePolicy.Draft d -> {
                for (String chunk : d.prefaceChunks()) {
                    blocks.add(TextBlock.builder().text(chunk).build());
                }
                blocks.add(toolCall(WorkflowDialoguePolicy.TOOL_PLAN_WORKFLOW, Map.of("prompt", d.prompt())));
            }
        }
        return blocks;
    }

    private static ToolUseBlock toolCall(String name, Map<String, Object> input) {
        // 显式 id：executeToolCalls 按 id 关联工具结果，不容框架自动生成缺失
        // content 双写 JSON：ToolExecutor 用 getContent() 做 schema 校验（required 字段从
        // content 解析），input 才是实际执行参数——两者都写，plan_workflow 的必填 prompt
        // 校验才能通过。
        String contentJson;
        try {
            contentJson = MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具参数序列化失败", e);
        }
        return ToolUseBlock.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .input(input)
                .content(contentJson)
                .build();
    }
}