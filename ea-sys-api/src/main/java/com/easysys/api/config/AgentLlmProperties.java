package com.easysys.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * yml「easysys.agent.llm」段 → LLM 主提供方配置（{@code HarnessAgentConfig} 装配模型位消费）。
 *
 * <p>apiKey 经 {@code ${EA_LLM_API_KEY:}} 占位从环境变量注入，任何文件不落真实 key。
 * enabled 缺省 false：未显式开启时批处理三路保持确定性 RuleModel，行为同 M6。
 * LLM 失效（key 缺失/认证失败/超时/schema 不符）由 AgentPolicy 降级为确定性 fallback，执行不中断。</p>
 */
@ConfigurationProperties(prefix = "easysys.agent.llm")
public class AgentLlmProperties {

    private boolean enabled = false;
    private String modelId = "openai:qwen3.7-plus";
    private String baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private long timeoutMs = 60_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

}