package com.easysys.api;

import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.mapper.WorkflowMapper;
import com.easysys.engine.service.DeliveryNotifier;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7 验收：AUDIENCE 人群节点（画布节点化执行语义）。
 * 断言：画布 AUDIENCE 节点成为批量成员来源（execute/dry-run 不带 audienceSnapshotId 仍成功，
 * 成员来自节点 audienceId 运行时圈选）；旧流程（无 AUDIENCE 节点）仍要求请求参数；
 * 校验：AUDIENCE 缺配置/人群不存在/重复节点拒绝；TRIGGER 定时缺 audienceId 在画布含 AUDIENCE
 * 节点时放行；view 响应注入 audienceName。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M7AudienceNodeTests {

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

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowMapper workflowMapper;

    @Autowired
    RedissonClient redisson;

    @MockBean
    DeliveryNotifier deliveryNotifier;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void login() throws Exception {
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
    }

    // ---------- 1. execute 不带 audienceSnapshotId：成员来自 AUDIENCE 节点 ----------

    @Test
    void executeSourcesMembersFromAudienceNode() throws Exception {
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
        createContact(contact("B", "13800000002", null, highRisk("王芳")));
        long audience = createAudience("m7-vip", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        // 不主动圈选：执行时应由 AUDIENCE 节点运行时圈选
        long wf = saveCanvas(audience);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.totalMembers").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='aud')].output.audienceId").value((int) audience))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='aud')].output.contacts").value(2));
    }

    // ---------- 2. dry-run 不带 audienceSnapshotId：同样由 AUDIENCE 节点圈选 ----------

    @Test
    void dryRunSourcesMembersFromAudienceNode() throws Exception {
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long audience = createAudience("m7-vip-dry", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveCanvas(audience);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workflows/{id}/dry-run", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.totalMembers").value(1));
    }

    // ---------- 3. 旧流程：无 AUDIENCE 节点时请求参数仍必填 ----------

    @Test
    void legacyFlowStillRequiresSnapshotIdWithoutAudienceNode() throws Exception {
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long audience = createAudience("m7-legacy", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveCanvasWithoutAudience(audience);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("audienceSnapshotId")));
    }

    // ---------- 4. 校验：AUDIENCE 缺配置 / 人群不存在 / 重复节点拒绝 ----------

    @Test
    void audienceNodeWithoutConfigRejected() throws Exception {
        String body = canvas("m7-bad", List.of(
                        node("trigger_1", "TRIGGER", "触发", null),
                        node("aud_1", "AUDIENCE", "人群", null),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "aud_1"), edge("aud_1", "end_1")));
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少 audienceId 配置")));
    }

    @Test
    void audienceNodeWithMissingAudienceRejected() throws Exception {
        createAudience("m7-ghost", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        String body = canvas("m7-ghost", List.of(
                        node("trigger_1", "TRIGGER", "触发", null),
                        node("aud_1", "AUDIENCE", "人群", Map.of("audienceId", 9999999)),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "aud_1"), edge("aud_1", "end_1")));
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("人群不存在")));
    }

    @Test
    void twoAudienceNodesRejected() throws Exception {
        long audience = createAudience("m7-two", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        String body = canvas("m7-two", List.of(
                        node("trigger_1", "TRIGGER", "触发", null),
                        node("aud_1", "AUDIENCE", "人群", Map.of("audienceId", audience)),
                        node("aud_2", "AUDIENCE", "人群2", Map.of("audienceId", audience)),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "aud_1"), edge("aud_1", "aud_2"), edge("aud_2", "end_1")));
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("至多允许 1 个")));
    }

    // ---------- 5. TRIGGER 定时缺 audienceId：画布含 AUDIENCE 节点时放行 ----------

    @Test
    void scheduledTriggerWithoutAudienceIdValidWithAudienceNode() throws Exception {
        long audience = createAudience("m7-sched", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("triggerType", "SCHEDULED");
        tc.put("cron", "0 30 9 * * ?");
        tc.put("timezone", "Asia/Shanghai");
        String body = canvas("m7-sched", List.of(
                        node("trigger_1", "TRIGGER", "触发", tc),
                        node("aud_1", "AUDIENCE", "人群", Map.of("audienceId", audience)),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "aud_1"), edge("aud_1", "end_1")));
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void scheduledTriggerWithoutAudienceIdRejectedWithoutAudienceNode() throws Exception {
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("triggerType", "SCHEDULED");
        tc.put("cron", "0 30 9 * * ?");
        tc.put("timezone", "Asia/Shanghai");
        String body = canvas("m7-sched-legacy", List.of(
                        node("trigger_1", "TRIGGER", "触发", tc),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "end_1")));
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("定时触发缺少 audienceId")));
    }

    // ---------- 6. view 响应：AUDIENCE 节点 config 注入 audienceName ----------

    @Test
    void viewInjectsAudienceNameIntoAudienceNode() throws Exception {
        long audience = createAudience("m7-view-aud", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long wf = saveCanvas(audience);
        mvc.perform(get("/api/workflows/{id}", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[?(@.key=='aud')].config.audienceId").value((int) audience))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='aud')].config.audienceName").value("m7-view-aud"));
    }

    // ---------- 基建（与 M4 同源） ----------

    private String canvas(String name, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", name);
        m.put("nodes", nodes);
        m.put("edges", edges);
        return asJson(m);
    }

    private long saveCanvas(long audience) throws Exception {
        String body = canvas("m7-audience", List.of(
                        node("trigger_1", "TRIGGER", "触发", null),
                        node("aud", "AUDIENCE", "人群圈选", Map.of("audienceId", audience)),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "aud"), edge("aud", "end_1")));
        var resp = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
        if (resp.getStatus() != 200) {
            System.err.println("SAVE-CANVAS-400 body=" + resp.getContentAsString() + " sent=" + body);
        }
        org.junit.jupiter.api.Assertions.assertEquals(200, resp.getStatus(), "save canvas failed");
        String s = resp.getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private long saveCanvasWithoutAudience(long audience) throws Exception {
        String body = canvas("m7-legacy", List.of(
                        node("trigger_1", "TRIGGER", "触发", null),
                        node("end_1", "END", "结束", null)),
                List.of(edge("trigger_1", "end_1")));
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
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

    private String bearer() {
        return "Bearer " + token;
    }

    private long createContact(String body) throws Exception {
        String s = mvc.perform(post("/api/contacts").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private long createTemplate(String channel, String name, String content) throws Exception {
        String s = mvc.perform(post("/api/templates").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + channel + "\",\"name\":\"" + name
                                + "\",\"content\":" + asJson(content) + "}"))
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
}