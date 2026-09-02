package com.easysys.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话式创建工作流验收（POST /api/workflows/ai-chat，SSE）：
 * 需求消息 → 查询轮（3 工具事件）→ 缺项追问；补充触发 → plan_workflow 生成轮
 * 框架 ask → REQUIRE_USER_CONFIRM 卡片事件；confirm 回填 → 工具执行 →
 * draft_ready（草稿 JSON，不自动落库）；挂起期间无确认的普通消息 → 400；
 * 取消（confirmed:false）→ DENIED → 「已取消」收尾、无草稿。
 *
 * <p>HITL 为框架级能力：本测试走确定性模型位（WorkflowDialogueModel），不依赖 LLM。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WorkflowDialogueIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    private static final String AUTH = "Authorization";

    @Autowired
    MockMvc mvc;

    @Autowired
    RedissonClient redisson;

    @Autowired
    ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
        // dev 种子含模板与人群；显式补一条短信模板 + 人群，保证查询轮有真实数据
        createTemplate("sms", "618大促通知", "亲爱的会员，618 大促限时开启，满 300 减 60！");
        createAudience("近30天未购买会员", "{\"op\":\"AND\",\"items\":[{\"field\":\"attribute.last_buy_days\",\"op\":\"gt\",\"value\":30}]}");
    }

    @Test
    void dialogueQueryThenDraftHitlThenConfirmProducesDraftReady() throws Exception {
        String sessionId = "it-dialogue-" + System.nanoTime();

        // ---- 轮 1：需求（缺触发）→ 查询轮 3 工具 + 追问回复，单次 SSE 流内完成 ----
        List<JsonNode> round1 = chat(sessionId, new ChatBody("向近30天未购买会员发送短信", sessionId, null));
        assertThat(typeNames(round1)).contains("TOOL_CALL_START", "TOOL_RESULT_END", "AGENT_RESULT");
        List<String> toolNames = collectToolStarts(round1);
        assertThat(toolNames).containsExactly("list_channels", "search_templates", "search_audiences");
        // 3 个查询工具全部执行成功
        assertThat(round1.stream()
                .filter(f -> "TOOL_RESULT_END".equals(f.path("type").asText()))
                .map(f -> f.path("state").asText()))
                .allMatch(s -> "SUCCESS".equals(s));
        // 追问：触发缺失（人群已表述 → 追问文本不引用人群名单，只追问触发）
        assertThat(allTextDeltas(round1)).anyMatch(t -> t.contains("触发时间"));
        assertThat(allTextDeltas(round1)).noneMatch(t -> t.contains("近30天未购买会员"));

        // ---- 轮 2：补充触发 → 生成轮 ask → REQUIRE_USER_CONFIRM 卡片事件 ----
        List<JsonNode> round2 = chat(sessionId, new ChatBody("每天上午9点延迟2小时执行", sessionId, null));
        List<String> types2 = typeNames(round2);
        assertThat(types2).contains("TOOL_CALL_START", "REQUIRE_USER_CONFIRM");
        JsonNode confirmEvent = firstOf(round2, "REQUIRE_USER_CONFIRM");
        assertThat(confirmEvent.path("toolCalls").get(0).path("name").asText()).isEqualTo("plan_workflow");
        assertThat(confirmEvent.path("toolCalls").get(0).path("input").path("prompt").asText()).contains("近30天未购买");
        // 确认前导文本（Draft 摘要 + 请确认）在卡片之前
        assertThat(allTextDeltas(round2)).anyMatch(t -> t.contains("请确认后生成"));

        // ---- 挂起期间：无 confirm 的普通消息 → 400（防御，避免丢消息） ----
        mvc.perform(post("/api/workflows/ai-chat").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody("再帮我加点东西", sessionId, null))))
                .andExpect(status().isBadRequest());

        // ---- 轮 3：确认 → plan_workflow 执行 → draft_ready（4 节点 3 边，含 DELAY） ----
        List<JsonNode> round3 = chat(sessionId,
                new ChatBody("确认生成", sessionId, new ChatBody.Confirm(true)));
        JsonNode draft = firstOf(round3, "draft_ready");
        assertThat(draft).isNotNull();
        JsonNode wf = draft.path("draft").path("workflowDraft");
        assertThat(wf.path("nodes")).hasSize(4);
        assertThat(wf.path("edges")).hasSize(3);
        assertThat(wf.path("nodes").get(0).path("type").asText()).isEqualTo("TRIGGER");
        assertThat(wf.path("nodes").get(0).path("config").path("cron").asText()).isEqualTo("0 0 9 * * ?");
        // 确认恢复执行不走模型输出工具，无 TOOL_CALL_START；执行与结语事件齐全
        assertThat(typeNames(round3)).contains("USER_CONFIRM_RESULT", "TOOL_RESULT_END", "AGENT_RESULT");
        assertThat(typeNames(round3)).doesNotContain("REQUIRE_USER_CONFIRM");
        // 收尾回复引用草稿规模
        assertThat(allTextDeltas(round3)).anyMatch(t -> t.contains("草稿已生成"));
    }

    @Test
    void cancelPendingConfirmationTerminatesWithoutDraft() throws Exception {
        String sessionId = "it-cancel-" + System.nanoTime();

        // 需求齐备 → 查询轮 + 生成轮 ask
        List<JsonNode> round1 = chat(sessionId, new ChatBody("每天上午9点向近30天未购买会员发送短信", sessionId, null));
        assertThat(typeNames(round1)).contains("REQUIRE_USER_CONFIRM");

        // 取消 → DENIED：不执行工具（无 TOOL_RESULT_* 事件），不再弹卡片，回复「已取消」，无草稿
        List<JsonNode> round2 = chat(sessionId,
                new ChatBody("取消", sessionId, new ChatBody.Confirm(false)));
        assertThat(typeNames(round2)).doesNotContain("REQUIRE_USER_CONFIRM", "draft_ready");
        assertThat(typeNames(round2)).contains("USER_CONFIRM_RESULT", "AGENT_RESULT");
        List<String> deltas = allTextDeltas(round2);
        assertThat(deltas).anyMatch(t -> t.contains("已取消"));
        assertThat(deltas).anyMatch(t -> t.contains("继续补充需求"));
    }

    @Test
    void confirmWithoutPendingAskIsRejected() throws Exception {
        String sessionId = "it-stale-" + System.nanoTime();
        // 无挂起确认（空会话）却带 confirm → 400
        mvc.perform(post("/api/workflows/ai-chat").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatBody("确认生成", sessionId, new ChatBody.Confirm(true)))))
                .andExpect(status().isBadRequest());
    }

    // ---- SSE 客户端 ----

    private List<JsonNode> chat(String sessionId, ChatBody body) throws Exception {
        MvcResult r = mvc.perform(post("/api/workflows/ai-chat")
                        .header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        // SseEmitter 流式响应：阻塞等待异步分派完成（emitter.complete 触发）
        r.getAsyncResult(30_000);
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> frames = new ArrayList<>();
        for (String chunk : raw.split("\n\n")) {
            for (String line : chunk.split("\n")) {
                if (line.startsWith("data:")) {
                    frames.add(objectMapper.readTree(line.substring("data:".length()).trim()));
                }
            }
        }
        assertThat(frames).isNotEmpty();
        return frames;
    }

    private static List<String> typeNames(List<JsonNode> frames) {
        return frames.stream().map(f -> f.path("type").asText()).toList();
    }

    private static JsonNode firstOf(List<JsonNode> frames, String type) {
        return frames.stream().filter(f -> type.equals(f.path("type").asText())).findFirst().orElse(null);
    }

    private static List<String> collectToolStarts(List<JsonNode> frames) {
        return frames.stream()
                .filter(f -> "TOOL_CALL_START".equals(f.path("type").asText()))
                .map(f -> f.path("toolCallName").asText())
                .toList();
    }

    private static List<String> allTextDeltas(List<JsonNode> frames) {
        List<String> out = new ArrayList<>();
        for (JsonNode f : frames) {
            if ("TEXT_BLOCK_DELTA".equals(f.path("type").asText())) {
                out.add(f.path("delta").asText());
            } else if ("TEXT_BLOCK_END".equals(f.path("type").asText())) {
                out.add(f.path("delta").asText());
            } else if ("TEXT_BLOCK_START".equals(f.path("type").asText())) {
                out.add(f.path("text").asText(""));
            } else if ("AGENT_RESULT".equals(f.path("type").asText())) {
                out.add(f.path("summary").asText());
            }
        }
        return out;
    }

    // ---- 基建 ----

    private long createTemplate(String channel, String name, String content) throws Exception {
        String s = mvc.perform(post("/api/templates").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + channel + "\",\"name\":\"" + name
                                + "\",\"content\":" + objectMapper.writeValueAsString(content) + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private long createAudience(String name, String ruleJson) throws Exception {
        String s = mvc.perform(post("/api/audiences").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "rule", "PLACEHOLDER"))
                                .replace("\"PLACEHOLDER\"", ruleJson)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private record ChatBody(String message, String sessionId, Confirm confirm) {
        private record Confirm(boolean confirmed) {
        }
    }
}