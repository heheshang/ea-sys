package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.AudienceSnapshot;
import com.easysys.api.entity.AudienceSnapshotMember;
import com.easysys.api.mapper.AudienceSnapshotMapper;
import com.easysys.api.mapper.AudienceSnapshotMemberMapper;
import com.easysys.api.service.EventQueueConsumer;
import com.easysys.api.service.EventQueueService;
import com.easysys.api.service.TriggerService;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.entity.DeliveryRecord;
import com.easysys.engine.entity.Execution;
import com.easysys.engine.mapper.DeliveryRecordMapper;
import com.easysys.engine.mapper.ExecutionMapper;
import com.easysys.engine.mapper.WorkflowMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6b 验收：触发双模式（定时 / 事件 / API）。
 * 断言：
 *   API 触发 → execution.trigger_type='API'，单用户入流，ACTION 真实触达落 delivery_record；
 *   事件触发 → 导入事件匹配 EVENT 流程单用户执行（trigger_type='EVENT'），eventFilter 不命中不执行；
 *   定时触发 → 到点圈选快照批量执行（trigger_type='SCHEDULED'，audience_snapshot_id 落库，member 触达）；
 *   防双跑 → 同 cron 槽位连续两次轮询仅产生一条 execution（RLock + 槽位记录）；
 *   立即触发 → 发布成功后即刻圈选快照批量执行（trigger_type='IMMEDIATE'），每次发布执行一次；
 *   缺 audienceId 的 IMMEDIATE 画布保存即被校验拒绝。
 * 数据访问一律走 MyBatis-Plus mapper，禁 JdbcTemplate。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M6TriggerTests {

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
        // 事件队列消费由测试显式驱动 pollOnce()，禁掉 @Scheduled 避免与断言竞态
        registry.add("easysys.trigger.event.enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    RedissonClient redisson;

    @Autowired
    TriggerService triggerService;

    @Autowired
    EventQueueConsumer eventQueueConsumer;

    @Autowired
    WorkflowMapper workflowMapper;

    @Autowired
    ExecutionMapper executionMapper;

    @Autowired
    AudienceSnapshotMapper audienceSnapshotMapper;

    @Autowired
    AudienceSnapshotMemberMapper audienceSnapshotMemberMapper;

    @Autowired
    DeliveryRecordMapper deliveryRecordMapper;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        inTenant(workflowMapper::testTruncateAll);
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 1. API 触发：单用户入流 ----------

    @Test
    void apiTriggerRunsSingleMemberWorkflow() throws Exception {
        long contact = createContact(contact("api-user", "13900000099", null, highRisk("触发")));
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        long wf = saveCanvas(null, template);
        publish(wf);

        mvc.perform(post("/api/workflows/{id}/triggers/api", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"payload\":{\"source\":\"crm\",\"orderId\":88}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        List<Execution> executions = executionsOf(wf);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getTriggerType()).isEqualTo("API");
        assertThat(executions.get(0).getTriggerPayload())
                .contains("source").contains("orderId").contains("crm");

        Long touched = inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executions.get(0).getId())));
        assertThat(touched).isEqualTo(1L);
    }

    // ---------- 2. 事件触发：eventName 匹配 + eventFilter 路由 ----------

    @Test
    void eventTriggerRunsOnImportAndFiltersByEventPayload() throws Exception {
        long contact = createContact(contact("evt-user", "13700000077", null, highRisk("事件")));
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        // 事件过滤：event.amount >= 100 才路由
        Map<String, Object> filter = Map.of(
                "op", "AND",
                "items", List.of(Map.of("field", "event.amount", "op", ">=", "value", 100)));
        long wf = saveCanvas(Map.of("triggerType", "EVENT", "eventName", "order_paid", "eventFilter", filter), template);
        publish(wf);

        // 未命中：amount=50 → 入流消费后仍不执行
        importEvent(contact, "order_paid", "2026-09-02T01:00:00Z", Map.of("amount", 50)).andExpect(status().isOk());
        eventQueueConsumer.pollOnce();
        assertThat(executionsOf(wf)).isEmpty();

        // 命中：amount=200 → 入流后 HTTP 已返回但尚未执行（异步解耦）→ 消费后单用户执行
        importEvent(contact, "order_paid", "2026-09-02T01:05:00Z", Map.of("amount", 200)).andExpect(status().isOk());
        assertThat(executionsOf(wf)).isEmpty();

        eventQueueConsumer.pollOnce();
        List<Execution> executions = executionsOf(wf);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getTriggerType()).isEqualTo("EVENT");
    }

    /**
     * 队列投递可靠性：命中事件入流后 stream 有 1 条消息（未消费不执行）；
     * pollOnce 消费成功 XACK（stream 清空）+ 落 execution。
     */
    @Test
    void eventMessagesConsumedAndAcked() throws Exception {
        long contact = createContact(contact("ack-user", "13600000033", null, highRisk("队列")));
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        long wf = saveCanvas(Map.of("triggerType", "EVENT", "eventName", "order_paid"), template);
        publish(wf);

        importEvent(contact, "order_paid", "2026-08-01T00:00:00Z", Map.of());
        RStream<String, String> stream = redisson.getStream(EventQueueService.STREAM);
        assertThat(stream.size()).isEqualTo(1L);
        assertThat(executionsOf(wf)).isEmpty();

        eventQueueConsumer.pollOnce();
        // 消费成功 XACK：组 pending 清空（XLEN 只增不减，用 PEL 判消费完成）
        assertThat(stream.listPending(EventQueueConsumer.GROUP,
                StreamMessageId.MIN, StreamMessageId.MAX, 100)).isEmpty();
        List<Execution> executions = executionsOf(wf);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getTriggerType()).isEqualTo("EVENT");
    }

    // ---------- 3. 定时触发：到点圈选快照批量执行 ----------

    @Test
    void scheduledTriggerCirclesSnapshotAndExecutes() throws Exception {
        createContact(contact("sched-user", "13600000055", null, highRisk("定时")));
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long wf = saveCanvas(Map.of("triggerType", "SCHEDULED", "cron", "* * * * * ?",
                "audienceId", audience, "timezone", "Asia/Shanghai"), template);
        publish(wf);
        // 回拨 published_at 使 cron 已到点，本次轮询即触发（不依赖等待真实时钟）
        inTenant(() -> workflowMapper.testBackdatePublishedAt(wf, 2));

        triggerService.scanScheduledDues();

        List<Execution> executions = executionsOf(wf);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getTriggerType()).isEqualTo("SCHEDULED");

        Long snapshotId = executions.get(0).getAudienceSnapshotId();
        assertThat(snapshotId).isNotNull();
        AudienceSnapshot snapshot = inTenant(() -> audienceSnapshotMapper.selectById(snapshotId));
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getStatus()).isEqualTo("ready");

        Long members = inTenant(() -> audienceSnapshotMemberMapper.selectCount(
                Wrappers.<AudienceSnapshotMember>lambdaQuery()
                        .eq(AudienceSnapshotMember::getSnapshotId, snapshotId)));
        assertThat(members).isEqualTo(1L);

        Long touched = inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executions.get(0).getId())));
        assertThat(touched).isEqualTo(1L);
    }

    // ---------- 4. 防双跑：同一 cron 槽位仅触发一次 ----------

    @Test
    void scheduledTriggerDoesNotDoubleRunWithinSlot() throws Exception {
        createContact(contact("dup-user", "13500000044", null, highRisk("防双跑")));
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        // cron 固定到 2000-01-01 00:00 这一个历史时刻：published_at 回拨到 1999 年 → 该槽位必然已到期；
        // 触发后 last 槽位 = 该时刻，next 超出 cron 年份范围 → 第二次轮询必然不再触发（不依赖墙钟）。
        long wf = saveCanvas(Map.of("triggerType", "SCHEDULED", "cron", "0 0 0 1 1 ? 2000",
                "audienceId", audience, "timezone", "Asia/Shanghai"), template);
        publish(wf);
        inTenant(() -> workflowMapper.testSetPublishedAt(wf, "1999-01-01T00:00:00Z"));

        triggerService.scanScheduledDues();
        triggerService.scanScheduledDues();

        assertThat(executionsOf(wf)).hasSize(1);
    }

    // ---------- 5. 立即触发：发布成功后即刻圈选快照批量执行 ----------

    @Test
    void immediateTriggerRunsOnPublish() throws Exception {
        createContact(contact("imm-user", "13400000022", null, highRisk("立即")));
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long wf = saveCanvas(Map.of("triggerType", "IMMEDIATE", "audienceId", audience), template);

        publish(wf);

        // 发布即执行：execution.trigger_type='IMMEDIATE'，快照圈选 1 人并真实下发
        List<Execution> executions = executionsOf(wf);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getTriggerType()).isEqualTo("IMMEDIATE");
        Long snapshotId = executions.get(0).getAudienceSnapshotId();
        assertThat(snapshotId).isNotNull();
        Long members = inTenant(() -> audienceSnapshotMemberMapper.selectCount(
                Wrappers.<AudienceSnapshotMember>lambdaQuery()
                        .eq(AudienceSnapshotMember::getSnapshotId, snapshotId)));
        assertThat(members).isEqualTo(1L);
        Long touched = inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executions.get(0).getId())));
        assertThat(touched).isEqualTo(1L);
    }

    /** 语义：每次发布都立即执行一次（无 cron 槽位，不依赖轮询与防双跑）。 */
    @Test
    void immediateTriggerRunsAgainOnRepublish() throws Exception {
        createContact(contact("imm2-user", "13400000021", null, highRisk("再发布")));
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        Map<String, Object> trigger = Map.of("triggerType", "IMMEDIATE", "audienceId", audience);
        String body = canvasBody("m6-imm2", "再发布", trigger, template);
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long wf = Long.parseLong(JsonPath.read(s, "$.data.id").toString());

        publish(wf); // v1 发布 → 第 1 次执行
        // 编辑新版本后再次发布（新草稿版本 v2）→ 第 2 次执行
        mvc.perform(put("/api/workflows/{id}", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        publish(wf);

        List<Execution> executions = executionsOf(wf);
        assertThat(executions).hasSize(2);
        assertThat(executions.get(0).getTriggerType()).isEqualTo("IMMEDIATE");
        assertThat(executions.get(1).getTriggerType()).isEqualTo("IMMEDIATE");
    }

    @Test
    void immediateTriggerWithoutAudienceRejectedAtSave() throws Exception {
        long template = createTemplate("sms", "短信关怀", "Hi ${name!}");
        String body = canvasBody("m6-imm3", "缺人群", Map.of("triggerType", "IMMEDIATE"), template);
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("立即触发缺少 audienceId 配置")));
    }

    // ---------- helpers ----------

    private List<Execution> executionsOf(long wf) {
        return inTenant(() -> executionMapper.selectList(
                Wrappers.<Execution>lambdaQuery().eq(Execution::getWorkflowId, wf)));
    }

    /** mapper 读写均在测试线程上执行：需显式租户上下文，避免租户插件抛"租户上下文缺失"。 */
    private <T> T inTenant(java.util.function.Supplier<T> supplier) {
        TenantContext.set(new TenantInfo(1L));
        try {
            return supplier.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Runnable runnable) {
        inTenant(() -> {
            runnable.run();
            return null;
        });
    }

    private org.springframework.test.web.servlet.ResultActions importEvent(long contactId, String eventName,
                                                                          String occurredAt, Object payload) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("contactId", contactId);
        item.put("eventName", eventName);
        item.put("occurredAt", occurredAt);
        item.put("payload", payload);
        return mvc.perform(post("/api/events").header(AUTH, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJson(Map.of("events", List.of(item)))));
    }

    private void publish(long wf) throws Exception {
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private long saveCanvas(Object triggerConfig, long templateId) throws Exception {
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canvasBody("m6-trigger", "触发双模式", triggerConfig, templateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private String canvasBody(String name, String description, Object triggerConfig, long templateId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("nodes", List.of(
                node("trigger", "TRIGGER", "开始", triggerConfig),
                node("act1", "ACTION", "发送短信", Map.of("channel", "sms", "templateId", templateId, "unitCost", 0.05)),
                node("end", "END", "结束", null)));
        m.put("edges", List.of(
                edge("trigger", "act1", null),
                edge("act1", "end", null)));
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
}