package com.easysys.api.config;

import com.easysys.api.dialogue.AssistantBeginWorkflowDialogueTool;
import com.easysys.api.dialogue.AssistantQueryStatsTool;
import com.easysys.api.dialogue.AssistantSearchAudiencesTool;
import com.easysys.api.dialogue.AssistantSearchKbTool;
import com.easysys.api.dialogue.AssistantSearchWorkflowsTool;
import com.easysys.api.dialogue.AssistantTriggerWorkflowTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 智能客服：独立 HarnessAgent 单例（与 workflowDialogueAgent 分开的会话状态域）。
 *
 * <p>能力：知识库问答（search_kb）、运营数据（query_stats）、人群圈定（search_audiences）、
 * 工作流触发（search_workflows + trigger_workflow，HITL 人工确认）、切换到工作流创建助手
 * （begin_workflow_dialogue，由控制器向前端发射 switch 事件，复用 /api/workflows/ai-chat）。
 *
 * <p>模型位为确定性 {@code AssistantPolicy}（决策即意图路由，无 LLM 亦可跑；
 * 接入真实模型如 ultrathink 时换 ModelRegistry 解析结果，流程/HITL/卡片零改动）。
 *
 * <p>状态存储与工作区隔离策略同 WorkflowDialogueConfig（Redis 分布式 store；
 * IsolationScope.USER 按 runtime userId（= tenantId）隔离）。
 */
@Configuration
public class AssistantConfig {

    /** 助手 sysPrompt（确定性模型不消费；LLM 接入时引导角色行为）。 */
    static final String SYS_PROMPT = """
            你是 AI 智能客服，为运营人员提供一站式支持：知识库问答（引用上传文档原文答复）、
            运营数据解读（到达率/留存率/转化漏斗/工作流效果）、人群圈定查看、已发布工作流的
            AI 触发（执行前必须人工确认）、创建运营工作流时切换到工作流创建助手。规则：绝不编造
            数据或引用不存在的人群/工作流；触发执行与生成草稿前必须等待用户明确确认；回答简洁、
            引用真实结果；用户表达创建/设计工作流意图时调用 begin_workflow_dialogue 切换会话。
            """;

    @Bean(destroyMethod = "close")
    public HarnessAgent assistantAgent(RedisDistributedStore agentscopeDistributedStore,
                                       AssistantSearchKbTool searchKbTool,
                                       AssistantQueryStatsTool queryStatsTool,
                                       AssistantSearchAudiencesTool searchAudiencesTool,
                                       AssistantSearchWorkflowsTool searchWorkflowsTool,
                                       AssistantTriggerWorkflowTool triggerWorkflowTool,
                                       AssistantBeginWorkflowDialogueTool beginWorkflowDialogueTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(searchKbTool);
        toolkit.registerAgentTool(queryStatsTool);
        toolkit.registerAgentTool(searchAudiencesTool);
        toolkit.registerAgentTool(searchWorkflowsTool);
        toolkit.registerAgentTool(triggerWorkflowTool);
        toolkit.registerAgentTool(beginWorkflowDialogueTool);

        return HarnessAgent.builder()
                .name("assistant")
                .description("AI 智能客服：知识库问答、运营数据、人群圈定、AI 触发工作流、创建工作流入口")
                .sysPrompt(SYS_PROMPT)
                .model(new com.easysys.agent.AssistantModel())
                .toolkit(toolkit)
                .distributedStore(agentscopeDistributedStore)
                .filesystem(new RemoteFilesystemSpec(agentscopeDistributedStore.baseStore())
                        .isolationScope(IsolationScope.USER))
                .maxIters(5)
                .build();
    }
}