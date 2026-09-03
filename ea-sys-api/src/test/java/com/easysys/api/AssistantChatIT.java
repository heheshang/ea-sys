package com.easysys.api;

import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.mapper.WorkflowMapper;
import com.easysys.engine.service.DeliveryNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 智能客服验收（POST /api/assistant/ai-chat，SSE；知识库文档管理）。
 *
 * <p>覆盖链路（确定性策略器，无 LLM）：文档上传 → KB 问答引用卡 + 原文引用回答；
 * 到达率/留存率 → stats 数据卡（多主题并发查询两张卡）；人群圈定 → 人群卡；
 * 触发已发布工作流 → 列表卡 → 单条自动发起 → REQUIRE_USER_CONFIRM HITL →
 * confirm 回填执行 → 触发结果卡；挂起期间普通消息 400；取消 → DENIED 收尾；
 * 创建意图 → begin_workflow_dialogue → switch_workflow_dialogue 事件；
 * 文档删除。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AssistantChatIT {

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
    WorkflowMapper workflowMapper;

    @Autowired
    RedissonClient redisson;

    @MockBean
    DeliveryNotifier deliveryNotifier;

    @Autowired
    ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        inTenant(workflowMapper::testTruncateAll);
        redisson.getKeys().flushall();
        when(deliveryNotifier.deliver(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
        // 种子：人群 + 命中该人群的会员，供圈子/触发链路使用
        createAudience("assistant-vip", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
    }

    // ---------- 1. 知识库问答（上传 → 提问 → 引用卡 + 原文回答） ----------

    @Test
    void kbQuestionAnsweredWithCitationCard() throws Exception {
        String sessionId = "it-kb-" + System.nanoTime();
        long docId = uploadDocument("会员权益说明.md", "会员权益包括：生日礼遇、积分翻倍、专属客服、免运费。"
                + "新会员注册后 7 天内可领取新人礼包。");
        assertThat(docId).isPositive();

        List<JsonNode> frames = chat(sessionId, new ChatBody("新会员有哪些权益？", sessionId, null));
        // KB 命中卡：data.hits 引用真实段落，含文档名与原文摘要
        JsonNode card = firstCard(frames, "kb");
        assertThat(card).isNotNull();
        assertThat(card.path("data").path("hits")).isNotEmpty();
        assertThat(card.path("data").path("hits").get(0).path("documentName").asText())
                .contains("会员权益说明");
        // 回答引用原文（第 1. 摘自…）并提示可追问
        assertThat(allTextDeltas(frames)).anyMatch(t -> t.contains("摘自《会员权益说明"));

        // 无关问题 → 未命中（kb 卡 hits 为空 + note 文案引导上传文档）
        List<JsonNode> miss = chat(sessionId, new ChatBody("这个产品多久才打折一次？", sessionId, null));
        JsonNode missCard = firstCard(miss, "kb");
        assertThat(missCard).isNotNull();
        assertThat(missCard.path("data").path("hits")).isEmpty();
        assertThat(allTextDeltas(miss)).anyMatch(t -> t.contains("暂未找到"));
    }

    // ---------- 2. 数据问答：留存率 → stats 卡 ----------

    @Test
    void retentionQuestionAnsweredWithStatsCard() throws Exception {
        String sessionId = "it-ret-" + System.nanoTime();
        List<JsonNode> frames = chat(sessionId, new ChatBody("最近30天留存率怎么样", sessionId, null));
        JsonNode card = firstCard(frames, "stats");
        assertThat(card).isNotNull();
        assertThat(card.path("data").path("topics").get(0).path("topic").asText()).isEqualTo("retention");
        assertThat(allTextDeltas(frames)).anyMatch(t -> t.contains("留存"));
    }

    /** 到达率 + 留存率同轮并发：两个 query_stats 调用、两张 stats 卡（按 toolCallId 隔离累积）。 */
    @Test
    void multiStatsTopicsRunConcurrently() throws Exception {
        String sessionId = "it-stats-" + System.nanoTime();
        List<JsonNode> frames = chat(sessionId, new ChatBody("查一下到达率和留存率", sessionId, null));
        assertThat(collectToolStarts(frames).stream().filter("query_stats"::equals).count()).isEqualTo(2);
        List<JsonNode> statsCards = cardsOf(frames, "stats");
        assertThat(statsCards).hasSize(2);
        List<String> topics = statsCards.stream()
                .map(c -> c.path("data").path("topics").get(0).path("topic").asText())
                .toList();
        assertThat(topics).containsExactlyInAnyOrder("channel", "retention");
        assertThat(allTextDeltas(frames)).anyMatch(t -> t.contains("渠道送达"));
        assertThat(allTextDeltas(frames)).anyMatch(t -> t.contains("留存"));
    }

    // ---------- 3. 人群圈定 ----------

    @Test
    void audienceIntentListsAudiencesCard() throws Exception {
        String sessionId = "it-aud-" + System.nanoTime();
        List<JsonNode> frames = chat(sessionId, new ChatBody("圈定一下人群", sessionId, null));
        JsonNode card = firstCard(frames, "audiences");
        assertThat(card).isNotNull();
        assertThat(card.path("data").get(0).path("name").asText()).contains("assistant-vip");
        assertThat(allTextDeltas(frames)).anyMatch(t -> t.contains("当前租户人群"));
    }

    // ---------- 4. AI 触发工作流（HITL 确认） ----------

    @Test
    void triggerWorkflowRequiresConfirmThenExecutes() throws Exception {
        String sessionId = "it-trigger-" + System.nanoTime();
        long wf = savePublishedCanvas("assistant-触发测试");

        // ---- 轮 1：触发意图 → 列表卡 → 唯一工作流自动发起 → 人工确认（单条 SSE 流完成） ----
        List<JsonNode> round1 = chat(sessionId, new ChatBody("触发执行", sessionId, null));
        List<String> tools = collectToolStarts(round1);
        assertThat(tools).contains("search_workflows", "trigger_workflow");
        JsonNode wfCard = firstCard(round1, "workflows");
        assertThat(wfCard).isNotNull();
        assertThat(wfCard.path("data").get(0).path("id").asLong()).isEqualTo(wf);
        JsonNode confirm = firstOf(round1, "REQUIRE_USER_CONFIRM");
        assertThat(confirm).isNotNull();
        assertThat(confirm.path("toolCalls").get(0).path("name").asText()).isEqualTo("trigger_workflow");
        // 导语告知唯一工作流（触发前文本）
        assertThat(allTextDeltas(round1)).anyMatch(t -> t.contains("assistant-触发测试"));

        // ---- 挂起期间：无 confirm 的普通消息 → 400（防御，避免跳过确认） ----
        mvc.perform(post("/api/assistant/ai-chat").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody("再帮我看看", sessionId, null))))
                .andExpect(status().isBadRequest());

        // ---- 轮 2：确认 → 真实执行 → 触发结果卡 + 摘要 ----
        List<JsonNode> round2 = chat(sessionId,
                new ChatBody("确认触发", sessionId, new ChatBody.Confirm(true)));
        assertThat(typeNames(round2)).contains("USER_CONFIRM_RESULT", "TOOL_RESULT_END", "AGENT_RESULT");
        JsonNode triggerCard = firstCard(round2, "trigger");
        assertThat(triggerCard).isNotNull();
        assertThat(triggerCard.path("data").path("workflowId").asLong()).isEqualTo(wf);
        assertThat(triggerCard.path("data").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(triggerCard.path("data").path("workflowName").asText()).contains("assistant-触发测试");
        assertThat(allTextDeltas(round2)).anyMatch(t -> t.contains("已触发工作流"));
    }

    /** 取消确认 → 工具不执行，回复「已取消」，无触发卡。 */
    @Test
    void cancelConfirmTerminatesWithoutTrigger() throws Exception {
        String sessionId = "it-trigger-cancel-" + System.nanoTime();
        savePublishedCanvas("assistant-取消测试");

        List<JsonNode> round1 = chat(sessionId, new ChatBody("触发执行", sessionId, null));
        assertThat(firstOf(round1, "REQUIRE_USER_CONFIRM")).isNotNull();

        List<JsonNode> round2 = chat(sessionId,
                new ChatBody("取消", sessionId, new ChatBody.Confirm(false)));
        assertThat(typeNames(round2)).contains("USER_CONFIRM_RESULT", "AGENT_RESULT");
        assertThat(cardsOf(round2, "trigger")).isEmpty();
        assertThat(allTextDeltas(round2)).anyMatch(t -> t.contains("已取消"));
    }

    // ---------- 5. 切换到工作流创建助手 ----------

    @Test
    void createIntentSwitchesToWorkflowDialogue() throws Exception {
        String sessionId = "it-create-" + System.nanoTime();
        List<JsonNode> frames = chat(sessionId, new ChatBody("创建一个运营工作流", sessionId, null));
        List<String> tools = collectToolStarts(frames);
        assertThat(tools).containsExactly("begin_workflow_dialogue");
        assertThat(typeNames(frames)).contains("switch_workflow_dialogue");
        assertThat(allTextDeltas(frames)).anyMatch(t -> t.contains("已切换到工作流创建助手"));
    }

    // ---------- 6. 取消与文档管理 ----------

    @Test
    void cancelWordRepliesWithoutTools() throws Exception {
        String sessionId = "it-cancel-" + System.nanoTime();
        List<JsonNode> frames = chat(sessionId, new ChatBody("取消", sessionId, null));
        assertThat(collectToolStarts(frames)).isEmpty();
        assertThat(cardsOf(frames, "kb")).isEmpty();
        assertThat(allTextDeltas(frames)).anyMatch(t -> t.contains("已取消"));
    }

    @Test
    void uploadListDeleteDocuments() throws Exception {
        long id = uploadDocument("活动规则.md", "双十一活动规则：满 300 减 60，活动时间 11 月 1 日至 11 日。");
        // 列表可见
        String list = mvc.perform(get("/api/assistant/documents").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(list, "$.data[*].name"))
                .anyMatch(n -> n.toString().contains("活动规则"));
        // 删除后不可见（软删文档行 + 物理删分块）
        mvc.perform(delete("/api/assistant/documents/{id}", id).header(AUTH, bearer()))
                .andExpect(status().isOk());
        String after = mvc.perform(get("/api/assistant/documents").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(after, "$.data[*].name"))
                .noneMatch(n -> n.toString().contains("活动规则"));
    }

    // ---- SSE 客户端 ----

    private List<JsonNode> chat(String sessionId, ChatBody body) throws Exception {
        MvcResult r = mvc.perform(post("/api/assistant/ai-chat")
                        .header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
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

    private static List<JsonNode> cardsOf(List<JsonNode> frames, String kind) {
        return frames.stream()
                .filter(f -> "assistant_card".equals(f.path("type").asText()) && kind.equals(f.path("kind").asText()))
                .toList();
    }

    private static JsonNode firstCard(List<JsonNode> frames, String kind) {
        return cardsOf(frames, kind).stream().findFirst().orElse(null);
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
            if ("TEXT_BLOCK_DELTA".equals(f.path("type").asText()) || "TEXT_BLOCK_END".equals(f.path("type").asText())) {
                out.add(f.path("delta").asText());
            } else if ("AGENT_RESULT".equals(f.path("type").asText())) {
                out.add(f.path("summary").asText());
            }
        }
        return out;
    }

    // ---- 基建（种子/上传，参考 M7 验收模式） ----

    private long uploadDocument(String name, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
        String s = mvc.perform(multipart("/api/assistant/documents").file(file).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    /** 保存并发布带 AUDIENCE 节点的画布工作流（触发链路候选）。 */
    private long savePublishedCanvas(String name) throws Exception {
        // /api/audiences 返回分页信封 {records, total, page, size}；取最新一个人群（@BeforeEach 种子）
        long audience = Long.parseLong(JsonPath.read(
                mvc.perform(get("/api/audiences").header(AUTH, bearer()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.data.records[0].id").toString());
        String body = canvas(name, List.of(
                        node("trigger_1", "TRIGGER", "触发", null),
                        node("aud", "AUDIENCE", "人群圈选", Map.of("audienceId", audience)),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "aud"), edge("aud", "end_1")));
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long wf = Long.parseLong(JsonPath.read(s, "$.data.id").toString());
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());
        return wf;
    }

    private String canvas(String name, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("nodes", nodes);
        m.put("edges", edges);
        return asJson(m);
    }

    private Map<String, Object> node(String key, String type, String name, Object config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("type", type);
        m.put("name", name);
        if (config != null) {
            m.put("config", config);
        }
        return m;
    }

    private Map<String, Object> edge(String source, String target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        return m;
    }

    private long createContact(String body) throws Exception {
        String s = mvc.perform(post("/api/contacts").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private long createAudience(String name, String ruleJson) throws Exception {
        String s = mvc.perform(post("/api/audiences").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(audienceBody(name, ruleJson)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private String audienceBody(String name, String ruleJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("rule", "PLACEHOLDER");
        return asJson(m).replace("\"PLACEHOLDER\"", ruleJson);
    }

    private String contact(String externalId, String phone, String email, Map<String, Object> attributes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalId", externalId);
        if (phone != null) {
            m.put("phone", phone);
        }
        if (email != null) {
            m.put("email", email);
        }
        m.put("attributes", attributes);
        m.put("tags", List.of());
        return asJson(m);
    }

    private Map<String, Object> highRisk(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("churn_risk", "HIGH");
        m.put("name", name);
        return m;
    }

    private String rule(String op, List<String> items) {
        return "{\"op\":\"" + op + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    private String cond(String field, String op, Object value) {
        return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + asJson(value) + "}";
    }

    private <T> T inTenant(Supplier<T> action) {
        TenantContext.set(new TenantInfo(1L));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Runnable action) {
        TenantContext.set(new TenantInfo(1L));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private String asJson(Object o) {
        try {
            return new ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private record ChatBody(String message, String sessionId, Confirm confirm) {
        private record Confirm(boolean confirmed) {
        }
    }
}