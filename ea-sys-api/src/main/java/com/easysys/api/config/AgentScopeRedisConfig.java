package com.easysys.api.config;

import io.agentscope.extensions.redis.RedisDistributedStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

/**
 * HarnessAgent 状态/工作区 Redis 持久化装配。
 *
 * <p>为对话（workflowDialogueAgent）与批处理三路（layer-strategy/churn-scan/workflow-generate）
 * 提供共享的 {@link JedisPooled}（独立于 engine 的 RedissonClient，互不干扰生命周期）与
 * {@link RedisDistributedStore}。键前缀 {@code easysys:agentscope:}：
 * 会话状态键 {@code easysys:agentscope:session:{userId}/{sessionId}:{key}}、工作区数据键
 * {@code easysys:agentscope:store:item:...}。租户维度由 RuntimeContext.userId（= tenantId）
 * 天然落在键路径中，见各 Config 的 isolationScope(USER) 装配。
 *
 * <p>依赖 agentscope-extensions-redis 的 Jedis 实现（redisson 已排除，避免 4.2.0 压过 engine
 * 3.52.0）；host/port 复用 spring.data.redis 配置，测试经 DynamicPropertySource 指向容器即可。
 */
@Configuration
public class AgentScopeRedisConfig {

    @Bean(destroyMethod = "close")
    public JedisPooled agentscopeJedisPooled(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return new JedisPooled(host, port);
    }

    @Bean
    public RedisDistributedStore agentscopeDistributedStore(JedisPooled agentscopeJedisPooled) {
        return RedisDistributedStore.fromJedis(agentscopeJedisPooled, "easysys:agentscope:");
    }
}