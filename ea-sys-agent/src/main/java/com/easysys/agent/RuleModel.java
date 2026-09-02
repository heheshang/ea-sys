package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AgentScope Java 2.0 确定性模型位：把策略规则（StrategyAgent.plan）包成框架 Model，
 * 主提供方逻辑经 ReActAgent 调用链执行，输出 JSON 由框架 native 结构化路径解析。
 *
 * <p>框架承载点（docs/04-agent-design.md §6）：main provider 不再是自研线程池直调，
 * 而是以确定性 Model 身份进入 AgentScope 的 ReActAgent 执行管道 —— 同一模型位
 * M6 换真实 LLM（ModelRegistry.resolve(...)）时编排/审计/降级链路零改动。</p>
 *
 * <p>规则抛出的异常原样经 Flux.error 传播（AgentExecutor 侧 catch 后以
 * provider_error:{SimpleName} 落入 fallback，测试断言兼容）。</p>
 */
public class RuleModel extends ChatModelBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 规则函数（允许受检异常，方法引用可适配 StrategyAgent.plan）。 */
    @FunctionalInterface
    public interface Rule {
        JsonNode apply(JsonNode input) throws Exception;
    }

    private final String name;
    private final Rule rule;

    /**
     * @param name 模型名（审计 model 字段，默认 "deterministic"）
     * @param rule 确定性决策函数：入参 JSON → 决策 JSON
     */
    public RuleModel(String name, Rule rule) {
        this.name = name;
        this.rule = rule;
        setNativeStructuredOutput(true);
    }

    @Override
    public String getModelName() {
        return name;
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
        Msg last = msgs.get(msgs.size() - 1);
        try {
            JsonNode input = MAPPER.readTree(last.getTextContent());
            JsonNode out = rule.apply(input);
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(out.toString()).build()))
                    .usage(ChatUsage.builder().inputTokens(0).outputTokens(0).build())
                    .finishReason("stop")
                    .build());
        } catch (Exception e) {
            return Flux.error(e);
        }
    }
}