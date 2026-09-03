package com.easysys.agent;

/**
 * 批处理智能体（AgentPolicy）运行参数。
 *
 * @param confidenceThreshold 置信度阈值：主输出 confidence 低于此值走 fallback（架构文档默认 0.7）
 * @param retries             主提供方失败重试次数（文档约定重试 2 次；由 HarnessAgent 装配层的
 *                            maxRetries 承接，AgentPolicy 侧不再自建重试循环）
 * @param timeoutMs           单次调用超时（确定性提供方即时返回；LLM 主提供方接入时取
 *                            {@code AgentLlmProperties.timeoutMs} 同量级 —— 3000ms 定值是确定性时代遗留，
 *                            接入真实 LLM 后 26 条消息 + schema 约束的完整推理超时，批处理全面 FALLBACK）
 */
public record AgentRunConfig(double confidenceThreshold, int retries, long timeoutMs) {

    /** 60s：确定性提供方毫秒级返回无感变化；LLM 长推理（多轮/大上下文）不再触发 3s 阻断。 */
    public static AgentRunConfig defaults() {
        return new AgentRunConfig(0.7, 2, 60_000);
    }
}