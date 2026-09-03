package com.easysys.api.config;

import com.easysys.agent.AgentType;
import com.easysys.agent.DeterministicChurnPlanner;
import com.easysys.agent.DeterministicLayerPlanner;
import com.easysys.agent.RuleModel;
import com.easysys.agent.StrategyAgent;
import com.easysys.agent.WorkflowPlanner;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 批处理三路（LAYER/CHURN/WORKFLOW）统一 HarnessAgent bean 装配：执行面由框架承载
 * （模型位、会话隔离、无状态一次性调用），合规闸门（schema 硬校验 + 置信度 + fallback）
 * 由 {@code AgentPolicy} 在执行后承担，审计仍落 audit_log。
 *
 * <p>模型位选择（与 docs/04-agent-design.md §6 一致）：LLM 主提供方开启（easysys.agent.llm
 * enabled + apiKey）时经 ModelRegistry 解析 OpenAI 兼容模型（openai:qwen3.7-plus → Token Plan
 * 端点），否则确定性 RuleModel（本里程碑默认）。resolve 失败回退确定性并告警 —— 启动不中断；
 * 运行时 LLM 挂（网络/认证）由 AgentPolicy 以 provider_error 落入确定性 fallback，执行不中断。</p>
 *
 * <p>会话语义：批处理无状态（disableSessionPersistence + RuntimeContext(userId=tenantId) 多租户
 * 隔离），stateStore 与工作区统一走 Redis（agentscope-extensions-redis Jedis 路径，键前缀
 * {@code easysys:agentscope:}），工作区按 IsolationScope.USER 按 runtime userId（= tenantId）
 * 隔离，不落本地文件系统。</p>
 */
@Configuration
public class HarnessAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentConfig.class);

    @Bean(destroyMethod = "close")
    public HarnessAgent layerStrategyAgent(RedisDistributedStore agentscopeDistributedStore,
                                           AgentLlmProperties llm) {
        DeterministicLayerPlanner planner = new DeterministicLayerPlanner();
        return batchAgent("layer-strategy", "人群分层策略生成（LAYER 批处理）",
                sysPrompt(AgentType.LAYER), planner, llm, agentscopeDistributedStore);
    }

    @Bean(destroyMethod = "close")
    public HarnessAgent churnScanAgent(RedisDistributedStore agentscopeDistributedStore,
                                       AgentLlmProperties llm) {
        DeterministicChurnPlanner planner = new DeterministicChurnPlanner();
        return batchAgent("churn-scan", "成员流失风险批量评估（CHURN 批处理）",
                sysPrompt(AgentType.CHURN), planner, llm, agentscopeDistributedStore);
    }

    @Bean(destroyMethod = "close")
    public HarnessAgent workflowGenerateAgent(RedisDistributedStore agentscopeDistributedStore,
                                              AgentLlmProperties llm) {
        WorkflowPlanner planner = new WorkflowPlanner();
        return batchAgent("workflow-generate", "运营工作流 DAG 生成（WORKFLOW 批处理）",
                sysPrompt(AgentType.WORKFLOW), planner, llm, agentscopeDistributedStore);
    }

    /** 批处理装配模板：单次迭代、无工具/无会话/无子代理，模型位按 LLM 开关选择。 */
    private static HarnessAgent batchAgent(String name, String description, String sysPrompt,
                                           StrategyAgent planner, AgentLlmProperties llm,
                                           RedisDistributedStore distributedStore) {
        return HarnessAgent.builder()
                .name(name)
                .description(description)
                .sysPrompt(sysPrompt)
                .model(primaryModel(llm, planner))
                .distributedStore(distributedStore)
                .filesystem(new RemoteFilesystemSpec(distributedStore.baseStore())
                        .isolationScope(IsolationScope.USER))
                .maxIters(1)
                .disableSessionPersistence()
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableSubagents()
                .disableWorkspaceContext()
                .disableToolResultEviction()
                .build();
    }

    /**
     * 主提供方模型：LLM 已启用（yml 注入 apiKey）时经 ModelRegistry 解析 OpenAI 兼容模型，
     * 否则确定性 RuleModel。resolve 失败（provider 未注册/参数非法）回退确定性并告警 ——
     * 启动期决定模型位；运行时 LLM 故障仍由 AgentPolicy 降级路径承载。
     */
    private static Model primaryModel(AgentLlmProperties llm, StrategyAgent planner) {
        if (llm != null && llm.isEnabled() && llm.getApiKey() != null && !llm.getApiKey().isBlank()) {
            try {
                return ModelRegistry.resolve(llm.getModelId(), ModelCreationContext.builder()
                        .apiKey(llm.getApiKey())
                        .baseUrl(llm.getBaseUrl())
                        .build());
            } catch (Exception e) {
                log.warn("LLM 主提供方解析失败，回退确定性 RuleModel: {}", e.getMessage());
            }
        }
        return new RuleModel("deterministic", planner::plan);
    }

    private static String sysPrompt(AgentType type) {
        return switch (type) {
            case LAYER -> "你是运营分层策略规划智能体：根据入参输出多通道触达分层策略 JSON";
            case ROUTER -> "你是触达路由决策智能体：根据入参输出单用户通道路由决策 JSON";
            case CHURN -> "你是流失风险评测智能体：根据入参输出成员流失风险批量评估 JSON";
            case WORKFLOW -> "你是运营工作流设计智能体：根据自然语言需求与租户模板/人群/通道上下文，输出工作流 DAG JSON";
        };
    }
}