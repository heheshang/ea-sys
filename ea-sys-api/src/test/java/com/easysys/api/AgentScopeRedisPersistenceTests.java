package com.easysys.api;

import com.easysys.api.config.AgentScopeRedisConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HarnessAgent 状态/工作区 Redis 持久化：验证 agentscope-extensions-redis（Jedis 路径）
 * 装配后，会话状态与工作区数据落 Redis 且按租户（userId=tenantId）隔离、本地
 * {@code data/agent-states} 不再写入。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("dev")
class AgentScopeRedisPersistenceTests {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    AgentScopeRedisConfig redisConfig;

    @Autowired
    RedisDistributedStore agentscopeDistributedStore;

    @Autowired
    JedisPooled agentscopeJedisPooled;

    @Autowired
    @Qualifier("workflowDialogueAgent")
    HarnessAgent workflowDialogueAgent;

    private static final String PREFIX = "easysys:agentscope:";

    @BeforeEach
    void flushKeys() {
        agentscopeJedisPooled.flushAll();
    }

    // ---- 1. 会话状态：按租户隔离 + Redis 键结构 ----

    @Test
    void sessionStateIsPersistedToRedisAndIsolatedByTenant() {
        String sessionId = "sess-tenant-iso";
        // 两租户同 sessionId 各存不同 AgentState
        agentscopeDistributedStore.agentStateStore().save("1", sessionId, "agent_state",
                AgentState.builder().userId("1").sessionId(sessionId).summary("租户1的会话摘要").build());
        agentscopeDistributedStore.agentStateStore().save("2", sessionId, "agent_state",
                AgentState.builder().userId("2").sessionId(sessionId).summary("租户2的会话摘要").build());

        // Redis 中两键并存，各回各值
        String key1 = PREFIX + "session:1/" + sessionId + ":agent_state";
        String key2 = PREFIX + "session:2/" + sessionId + ":agent_state";
        assertThat(agentscopeJedisPooled.exists(key1)).isTrue();
        assertThat(agentscopeJedisPooled.exists(key2)).isTrue();

        var t1 = agentscopeDistributedStore.agentStateStore()
                .get("1", sessionId, "agent_state", AgentState.class).orElseThrow();
        var t2 = agentscopeDistributedStore.agentStateStore()
                .get("2", sessionId, "agent_state", AgentState.class).orElseThrow();
        assertThat(t1.getSummary()).contains("租户1");
        assertThat(t2.getSummary()).contains("租户2");

        // 租户隔离：同 sessionId 读不到对方的键
        assertThat(agentscopeDistributedStore.agentStateStore()
                .get("1", sessionId, "agent_state", AgentState.class).orElseThrow().getSummary())
                .doesNotContain("租户2");
    }

    // ---- 2. 工作区文件：走 Redis store 且按租户命名空间隔离 ----

    @Test
    void workspaceFilesGoToRedisStoreIsolatedByTenant() throws Exception {
        WorkspaceManager wm1 = workflowDialogueAgent.workspaceFor("1", "sess-ws");
        WorkspaceManager wm2 = workflowDialogueAgent.workspaceFor("2", "sess-ws");

        RuntimeContext rc1 = RuntimeContext.builder().userId("1").sessionId("sess-ws").build();
        RuntimeContext rc2 = RuntimeContext.builder().userId("2").sessionId("sess-ws").build();

        wm1.appendUtf8WorkspaceRelative(rc1, "MEMORY.md", "租户1的长期记忆\n");
        wm2.appendUtf8WorkspaceRelative(rc2, "MEMORY.md", "租户2的长期记忆\n");

        // 读回各自内容
        assertThat(wm1.readMemoryMd(rc1)).contains("租户1的长期记忆");
        assertThat(wm2.readMemoryMd(rc2)).contains("租户2的长期记忆");
        // 租户间不串
        assertThat(wm1.readMemoryMd(rc1)).doesNotContain("租户2的长期记忆");

        // 键确实在 Redis store 命名空间下，且按租户隔离：
        // USER 隔离 → namespace [agents, workflow-dialogue, users, {uid}, root]，键含 \0users\0{uid}\0
        Set<String> remoteKeys = agentscopeJedisPooled.keys(PREFIX + "store:item:*");
        assertThat(remoteKeys).anyMatch(k -> k.contains("\u0000users\u00001\u0000"));
        assertThat(remoteKeys).anyMatch(k -> k.contains("\u0000users\u00002\u0000"));
    }

    // ---- 3. 装配语义：stateStore 为 Redis 实现，本地 data/agent-states 无新增 ----

    @Test
    void assembledStateStoreIsRedisAndLocalStateDirNotWritten() throws Exception {
        // 装配后的 agent 会话状态存储必须是 Redis 实现（JsonFile 路径已被替换）
        assertThat(workflowDialogueAgent.getStateStore().getClass().getSimpleName())
                .isEqualTo("RedisAgentStateStore");

        Path localStateDir = Path.of("data/agent-states");
        Set<String> before;
        if (Files.exists(localStateDir)) {
            try (var s = Files.list(localStateDir)) {
                before = s.map(p -> p.toString()).collect(java.util.stream.Collectors.toSet());
            }
        } else {
            before = Set.of();
        }

        // 触发一次工作区写入 + 会话状态保存（均走 Redis），data/agent-states 不应新增文件
        WorkspaceManager wm = workflowDialogueAgent.workspaceFor("9", "sess-local");
        wm.appendUtf8WorkspaceRelative(RuntimeContext.builder().userId("9").sessionId("sess-local").build(),
                "MEMORY.md", "probe\n");
        agentscopeDistributedStore.agentStateStore().save("9", "sess-local", "agent_state",
                AgentState.builder().userId("9").sessionId("sess-local").summary("probe").build());

        if (Files.exists(localStateDir)) {
            try (var s = Files.list(localStateDir)) {
                assertThat(s.map(p -> p.toString()).collect(java.util.stream.Collectors.toSet()))
                        .isEqualTo(before);
            }
        }
    }
}