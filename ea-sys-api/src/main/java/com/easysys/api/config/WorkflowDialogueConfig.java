package com.easysys.api.config;

import com.easysys.api.dialogue.WorkflowListChannelsTool;
import com.easysys.api.dialogue.WorkflowPlanTool;
import com.easysys.api.dialogue.WorkflowSearchAudiencesTool;
import com.easysys.api.dialogue.WorkflowSearchTemplatesTool;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 对话式创建工作流：HarnessAgent 单例。
 *
 * <p>承载人-AI 流式对话（会话持久化 + ReAct 工具循环 + SSE 事件流 + HITL）。
 * 模型位为确定性 {@code WorkflowDialogueModel}（无 LLM 主提供方时同路径可跑；
 * 接入真实模型如 ultrathink 时换 ModelRegistry 解析结果，流程/HITL 零改动）。
 *
 * <p>权限：不设 permissionContext（默认轻量路径直接调 ToolBase#checkPermissions，
 * 查询工具显式 allow、plan_workflow 工具 ask → 框架 RequireUserConfirmEvent HITL 闸门）。
 *
 * <p>状态存储：第一版本地 JsonFileAgentStateStore（data/ 已 gitignore）；
 * 生产可切 agentscope-extensions-redis 的 RedisAgentStateStore（未评估不上）。
 */
@Configuration
public class WorkflowDialogueConfig {

    /** 对话 sysPrompt（确定性模型不消费；LLM 接入时引导角色行为）。 */
    static final String SYS_PROMPT = """
            你是运营工作流创建助手。你的任务是通过对话澄清工作流需求：触发时机（如每天/每周/每月、
            具体几点）、目标人群（现有人群名或人群特征描述）、发送通道（短信/邮件/企微等）与发送内容
            （匹配模板或描述文案）。需求要素齐备后，向用户复述确认，得到明确确认后再调用 plan_workflow
            生成工作流草稿。规则：绝不编造数据，查询工具输出为只读参考；生成草稿前必须等待用户明确确认；
            用户取消时停止本轮并友好收尾。
            """;

    @Bean(destroyMethod = "close")
    public HarnessAgent workflowDialogueAgent(WorkflowListChannelsTool listChannelsTool,
                                              WorkflowSearchTemplatesTool searchTemplatesTool,
                                              WorkflowSearchAudiencesTool searchAudiencesTool,
                                              WorkflowPlanTool planTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(listChannelsTool);
        toolkit.registerAgentTool(searchTemplatesTool);
        toolkit.registerAgentTool(searchAudiencesTool);
        toolkit.registerAgentTool(planTool);

        return HarnessAgent.builder()
                .name("workflow-dialogue")
                .description("对话式创建工作流：澄清触发/人群/通道需求，确认后生成 DAG 草稿")
                .sysPrompt(SYS_PROMPT)
                .model(new com.easysys.agent.WorkflowDialogueModel())
                .toolkit(toolkit)
                .stateStore(new JsonFileAgentStateStore(Path.of("data/agent-states")))
                .maxIters(4)
                .build();
    }
}