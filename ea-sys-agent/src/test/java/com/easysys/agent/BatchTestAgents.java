package com.easysys.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Path;

/**
 * 批处理 HarnessAgent 装配（测试侧）：与 {@code HarnessAgentConfig#batchAgent} 同构 ——
 * 确定性 RuleModel 模型位（默认，绝大多数语义测试）或 LLM 模型位（真连通 / 故障注入），
 * 无状态（disableSessionPersistence）+ 无工具 + 单次迭代，agent 语义测试只关心闸门链路。
 */
final class BatchTestAgents {

    private BatchTestAgents() {
    }

    /** 确定性模型位：plan 逻辑即模型输出（RuleModel），与生产装配完全一致。 */
    static HarnessAgent deterministic(StrategyAgent planner) {
        return build(new RuleModel("deterministic", planner::plan));
    }

    /** LLM 模型位：ModelRegistry 解析 OpenAI 兼容模型（Token Plan 端点由调用方注入）。 */
    static HarnessAgent llm(String baseUrl, String apiKey) {
        Model model = ModelRegistry.resolve("openai:qwen3.7-plus",
                ModelCreationContext.builder().apiKey(apiKey).baseUrl(baseUrl).build());
        return build(model);
    }

    private static HarnessAgent build(Model model) {
        return HarnessAgent.builder()
                .name("test-batch")
                .description("batch agent under test")
                .sysPrompt("你是运营智能体：根据入参输出 JSON 决策")
                .model(model)
                .stateStore(new JsonFileAgentStateStore(Path.of("target/test-agent-states")))
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
}