package com.easysys.agent;

/**
 * AgentExecutor LLM 主提供方配置（源自 application.yml 的 easysys.agent.llm 段）。
 *
 * <p>启用条件：{@code enabled == true} 且 apiKey 非空——否则主提供方保持确定性
 * RuleModel（M6 行为不变）。LLM 接入后原有的 tryPlan → schema 硬校验 → 置信度闸门
 * → 确定性 fallback 链路零改动地即刻生效（docs/04-agent-design.md §6）。</p>
 *
 * @param enabled  是否启用 LLM 主提供方（缺省 false，显式开启后才替换确定性 RuleModel）
 * @param modelId  框架模型 id，如 openai:qwen3.7-plus（OpenAI 兼容扩展前缀 + Token Plan 模型名）
 * @param baseUrl  OpenAI 兼容端点（Token Plan 套餐专属，与通用 key 不互通）
 * @param apiKey   Token Plan API Key（yml 经 ${EA_LLM_API_KEY:} 占位注入，不入库）
 * @param timeoutMs LLM 单次调用超时（qwen3.7-plus 为 reasoning 模型，默认 60s 覆盖其 ~20s 响应）
 */
public record AgentLlmConfig(boolean enabled, String modelId, String baseUrl, String apiKey, long timeoutMs) {

    /** 未启用/缺配置的默认态：主提供方保持确定性 RuleModel。 */
    public static AgentLlmConfig disabled() {
        return new AgentLlmConfig(false, "", "", "", 0);
    }

    /** LLM 真正生效需同时满足：显式启用 + 已注入 apiKey。 */
    public boolean active() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}