package com.easysys.agent;

/**
 * 批处理智能体（AgentPolicy）运行参数。
 *
 * @param confidenceThreshold 置信度阈值：主输出 confidence 低于此值走 fallback（架构文档默认 0.7）
 * @param retries             主提供方失败重试次数（文档约定重试 2 次；由 HarnessAgent 装配层的
 *                            maxRetries 承接，AgentPolicy 侧不再自建重试循环）
 * @param timeoutMs           单次调用超时（确定性提供方即时返回；LLM 主提供方接入时传入对应超时）
 */
public record AgentRunConfig(double confidenceThreshold, int retries, long timeoutMs) {

    public static AgentRunConfig defaults() {
        return new AgentRunConfig(0.7, 2, 3000);
    }
}