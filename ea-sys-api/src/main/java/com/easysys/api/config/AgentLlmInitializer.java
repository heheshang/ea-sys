package com.easysys.api.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时把 yml 绑定的 easysys.agent.llm 配置注入 AgentExecutor。
 * 需在 context 完全就绪后执行（@ConfigurationProperties 绑定完成后），故用 ApplicationRunner。
 */
@Component
public class AgentLlmInitializer implements ApplicationRunner {

    private final AgentLlmProperties properties;

    public AgentLlmInitializer(AgentLlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AgentLlmProperties.apply(properties);
    }
}