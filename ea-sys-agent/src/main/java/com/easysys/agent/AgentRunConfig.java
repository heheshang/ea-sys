package com.easysys.agent;

/**
 * AgentExecutor 运行参数。
 *
 * @param confidenceThreshold 置信度阈值：主输出 confidence 低于此值走 fallback（架构文档默认 0.7）
 * @param retries             主提供方失败重试次数（文档约定重试 2 次）
 * @param timeoutMs           单次调用超时（确定性提供方即时返回；LLM 实现消费此配置）
 */
public record AgentRunConfig(double confidenceThreshold, int retries, long timeoutMs) {

    public static AgentRunConfig defaults() {
        return new AgentRunConfig(0.7, 2, 3000);
    }
}