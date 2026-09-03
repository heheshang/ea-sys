package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.Contact;
import com.easysys.api.mapper.ContactMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.entity.DeliveryRecord;
import com.easysys.engine.mapper.DeliveryRecordMapper;
import com.easysys.engine.mapper.WorkflowMapper;
import com.easysys.engine.service.DeliveryNotifier;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 验收：真实触达执行链路。
 * 断言：模板渲染 → 通道下发 → delivery_record 落库（幂等键语义）→ 频率控制（第二次执行不再下发）
 * → 治理拦截（status/suppression）→ 未注册通道导致节点 FAILED（报告可见病根）。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M3TouchTests {

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
        // 频率窗口覆盖回 24h：本测试断言「窗口内二次执行被 userRecent 拦截」的语义
        registry.add("easysys.frequency.user-window-hours", () -> "24");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowMapper workflowMapper;

    @Autowired
    DeliveryRecordMapper deliveryRecordMapper;

    @Autowired
    ContactMapper contactMapper;

    @Autowired
    RedissonClient redisson;

    @MockBean
    DeliveryNotifier deliveryNotifier;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void login() throws Exception {
        inTenant(workflowMapper::testTruncateAll);
        // 频率计数键跨测试隔离（TRUNCATE 会重置 contact id，Redis 必须同步清空）
        redisson.getKeys().flushall();
        // 回调服务（notify）不在测试环境：受理成功即落 SENT 待回执；真正触达由回执接口测试单独覆盖
        when(deliveryNotifier.deliver(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 主链路 ----------

    @Test
    void realExecuteRendersTemplateSendsAndRecordsDelivery() throws Exception {
        // 2 个高流失活跃成员；模板 1=sms（id 1，画布引用）、2=push（画布引用，本链路不走）
        createContact(contactBody("A", "13800000001", highRisk("张伟"), List.of()));
        createContact(contactBody("C", "13800000003", highRisk("李静"), List.of("vip")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));

        createTemplate("sms", "短信关怀", "亲爱的${name!}，您有一份专属福利待领取");
        createTemplate("push", "站内推送", "APP 推送占位");

        long wf = saveCanvas(audience);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        // 执行 → 真实下发：sms 2 条，push 0
        String body = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.totalMembers").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].contacts").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.sent").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.alreadySent").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.failed").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.estimatedCost").value(0.1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='push')].contacts").value(0))
                .andReturn().getResponse().getContentAsString();
        long executionId = Long.parseLong(JsonPath.read(body, "$.data.executionId").toString());

        // 下发记录：每人一行，模板渲染展开
        Long rows = inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionId)));
        assertThat(rows).isEqualTo(2L);
        List<String> contents = inTenant(() -> deliveryRecordMapper.selectList(
                        Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionId)
                                .orderByAsc(DeliveryRecord::getContactId)))
                .stream().map(DeliveryRecord::getContent).toList();
        assertThat(contents).containsExactly("亲爱的张伟，您有一份专属福利待领取", "亲爱的李静，您有一份专属福利待领取");
        // 异步回调语义：受理成功 = SENT（待回执），真正触达需通道回执（经 notify 回调）后为 DELIVERED
        List<String> statuses = inTenant(() -> deliveryRecordMapper.selectList(
                        Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionId)))
                .stream().map(DeliveryRecord::getStatus).distinct().toList();
        assertThat(statuses).containsExactly("SENT");
        Long channelMsgCount = inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionId)
                        .likeRight(DeliveryRecord::getChannelMsgId, "console-")));
        assertThat(channelMsgCount).isEqualTo(2L);

        // 模拟 notify 回执：SENT → DELIVERED；重复回调幂等不回退
        List<String> msgIds = inTenant(() -> deliveryRecordMapper.selectList(
                        Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionId)))
                .stream().map(DeliveryRecord::getChannelMsgId).toList();
        for (String msgId : msgIds) {
            mvc.perform(post("/api/deliveries/callback")
                            .header("X-Internal-Token", "ea-sys-notify-dev-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"channelMsgId\":\"" + msgId + "\",\"tenantId\":1,\"status\":\"DELIVERED\"}"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/deliveries/callback")
                            .header("X-Internal-Token", "ea-sys-notify-dev-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"channelMsgId\":\"" + msgId + "\",\"tenantId\":1,\"status\":\"FAILED\",\"error\":\"回退尝试\"}"))
                    .andExpect(status().isOk());
        }
        List<String> after = inTenant(() -> deliveryRecordMapper.selectList(
                        Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionId)))
                .stream().map(DeliveryRecord::getStatus).distinct().toList();
        assertThat(after).containsExactly("DELIVERED");

        // 报告重查与执行一致
        mvc.perform(get("/api/workflows/executions/{id}/report", executionId).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.sent").value(2));
    }

    // ---------- 幂等 + 频率 ----------

    @Test
    void secondExecuteSameSnapshotBlockedByFrequencyAndNeverDoubleSends() throws Exception {
        createContact(contactBody("A", "13800000001", highRisk("张伟"), List.of()));
        createContact(contactBody("C", "13800000003", highRisk("李静"), List.of()));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        createTemplate("sms", "短信关怀", "亲爱的${name!}，您有一份专属福利待领取");
        createTemplate("push", "站内推送", "APP 推送占位");
        long wf = saveCanvas(audience);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        String first = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.sent").value(2))
                .andReturn().getResponse().getContentAsString();
        long exec1 = Long.parseLong(JsonPath.read(first, "$.data.executionId").toString());

        // 同人群再次执行：频率窗口内（user-window-hours=24）不再下发，计入 skipped.userRecent
        String second = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.sent").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.alreadySent").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.skipped.userRecent").value(2))
                .andReturn().getResponse().getContentAsString();
        long exec2 = Long.parseLong(JsonPath.read(second, "$.data.executionId").toString());

        // 两次执行合计仍只下发 2 条（频率拦截先行，无重复触达）
        Long total = inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().in(DeliveryRecord::getExecutionId, exec1, exec2)));
        assertThat(total).isEqualTo(2L);
    }

    // ---------- 治理拦截 ----------

    @Test
    void executeSkipsInactiveAndSuppressedContacts() throws Exception {
        // A 活跃可发；B silent；C 活跃但退订 sms → 仅 A 收到
        long a = createContact(contactBody("A", "13800000001", highRisk("张伟"), List.of()));
        long b = createContact(contactBody("B", "13800000002", highRisk("王芳"), List.of(), "silent"));
        long c = createContact(contactBody("C", "13800000003", highRisk("李静"), List.of()));
        Contact c1 = inTenant(() -> contactMapper.selectById(c));
        c1.setSuppression("{\"channels\":[\"sms\"]}");
        inTenant(() -> contactMapper.updateById(c1));

        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));

        createTemplate("sms", "短信关怀", "亲爱的${name!}，您有一份专属福利待领取");
        createTemplate("push", "站内推送", "APP 推送占位");
        long wf = saveCanvas(audience);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        String body = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].contacts").value(3))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.sent").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.skipped.status").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].output.skipped.suppressed").value(1))
                .andReturn().getResponse().getContentAsString();
        long executionId = Long.parseLong(JsonPath.read(body, "$.data.executionId").toString());

        // 治理拦截不产生下发记录：仅 A 一行
        List<Long> contacts = inTenant(() -> deliveryRecordMapper.selectList(
                        Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionId)
                                .orderByAsc(DeliveryRecord::getContactId)))
                .stream().map(DeliveryRecord::getContactId).toList();
        assertThat(contacts).containsExactly(a);
        assertThat(contacts).doesNotContain(b, c);
    }

    // ---------- 结构失败可见 ----------

    @Test
    void executeWithUnregisteredChannelFailsNodeAndExecution() throws Exception {
        // 低流失成员 → 兜底 push 分支；push 无适配器 → 节点 FAILED、执行 FAILED、报告可见病根
        createContact(contactBody("D", "13800000004", lowRisk(), List.of()));
        long audience = createAudience("low-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "LOW"))));
        createTemplate("sms", "短信关怀", "亲爱的${name!}，您有一份专属福利待领取");
        createTemplate("push", "站内推送", "APP 推送占位");
        long wf = saveCanvas(audience);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.error").value(
                        org.hamcrest.Matchers.containsString("未注册的通道适配器")))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='push')].status").value("FAILED"));
    }

    // ---------- helpers（复用 M2 模式） ----------

    private long saveCanvas(long audienceId) throws Exception {
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canvasBody("churn-push", "回归关怀", audienceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    /** 画布含 AUDIENCE 人群节点：批量成员来源一律由节点圈选。 */
    private String canvasBody(String name, String description, long audienceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("nodes", List.of(
                node("trigger", "TRIGGER", "开始", null),
                node("aud", "AUDIENCE", "人群圈选", Map.of("audienceId", audienceId)),
                node("cond", "CONDITION", "高流失分流", null),
                node("sms", "ACTION", "短信",
                        Map.of("channel", "sms", "templateId", 1, "unitCost", 0.05)),
                node("push", "ACTION", "推送",
                        Map.of("channel", "push", "templateId", 2)),
                node("end", "END", "结束", null)));
        m.put("edges", List.of(
                edge("trigger", "aud", null),
                edge("aud", "cond", null),
                edge("cond", "sms", "{\"op\":\"AND\",\"items\":[{\"field\":\"contact.churn_risk\",\"op\":\"equals\",\"value\":\"HIGH\"}]}"),
                edge("cond", "push", null),
                edge("sms", "end", null),
                edge("push", "end", null)));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> edge(String source, String target, String conditionJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        if (conditionJson != null) {
            m.put("condition", parseJson(conditionJson));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String s) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private long circle(long audienceId) throws Exception {
        String s = mvc.perform(post("/api/audiences/{id}/snapshot", audienceId).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ready"))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private String contactBody(String externalId, String phone, Map<String, Object> attributes, List<String> tags) {
        return contactBody(externalId, phone, attributes, tags, null);
    }

    private String contactBody(String externalId, String phone, Map<String, Object> attributes,
                               List<String> tags, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalId", externalId);
        m.put("phone", phone);
        if (status != null) {
            m.put("status", status);
        }
        m.put("attributes", attributes);
        m.put("tags", tags);
        return asJson(m);
    }

    private Map<String, Object> highRisk(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("churn_risk", "HIGH");
        m.put("name", name);
        return m;
    }

    private Map<String, Object> lowRisk() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("churn_risk", "LOW");
        m.put("name", "赵敏");
        return m;
    }

    private String audienceBody(String name, String ruleJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("rule", "PLACEHOLDER");
        return asJson(m).replace("\"PLACEHOLDER\"", ruleJson);
    }

    private String asJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String rule(String op, List<String> items) {
        return "{\"op\":\"" + op + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    private String cond(String field, String op, Object value) {
        return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + asJson(value) + "}";
    }

    /** 在默认租户上下文内执行 mapper 调用（租户插件要求）。 */
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
}