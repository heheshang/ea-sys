package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.AudienceSnapshot;
import com.easysys.api.entity.ContactAttribute;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.AudienceSnapshotMapper;
import com.easysys.api.mapper.ContactAttributeMapper;
import com.easysys.api.mapper.EventMapper;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 验收：留存看板（漏斗/区间留存/渠道效果/工作流效果）+ 流失预警 CHURN Agent（规则降级）。
 * 漏斗：圈选→执行→触达三阶段人数与转化率；留存：事件窗口活跃信号 cohort/retained；
 * 渠道：送达/失败/去重触达/送达率；工作流效果：最近执行触达与 N 天内留存。
 * 流失扫描：N 天未活跃 = HIGH 规则批量评估 → contact_attribute.churn_risk 回写 + audit_log(CHURN)。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M5RetentionTests {

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
    DeliveryRecordMapper deliveryRecordMapper;

    @Autowired
    AudienceSnapshotMapper audienceSnapshotMapper;

    @Autowired
    ContactAttributeMapper contactAttributeMapper;

    @Autowired
    AgentAuditMapper agentAuditMapper;

    @Autowired
    EventMapper eventMapper;

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

    // ---------- 1. 事件导入幂等 ----------

    @Test
    void eventImportIsIdempotent() throws Exception {
        long a = createContact(contact("A", "13800000001", null, highRisk("张伟")));
        String event = "{\"events\":[{\"contactId\":" + a
                + ",\"eventName\":\"page_view\",\"occurredAt\":\"2026-08-25T10:00:00Z\"}]}";

        mvc.perform(post("/api/events").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.duplicates").value(0));

        // 同 (tenant, contact, eventName, occurredAt) 重复上报 → 忽略
        mvc.perform(post("/api/events").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(0))
                .andExpect(jsonPath("$.data.duplicates").value(1));

        assertThat(inTenant(() -> eventMapper.selectCount(null))).isEqualTo(1L);
    }

    // ---------- 2. 漏斗：圈选→执行→触达 ----------

    @Test
    void funnelCountsSeededExecutedReached() throws Exception {
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
        createContact(contact("B", "13800000002", null, highRisk("王芳")));
        createContact(contact("C", "13800000003", null, highRisk("李静")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        assertThat(inTenant(() -> audienceSnapshotMapper.selectById(snapshot)).getMemberCount()).isEqualTo(3);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveSmokeCanvas("m5-funnel", "漏斗冒烟");
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());
        String exec = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionIdOf(exec)))))
                .isEqualTo(3L);

        // 工作流维度
        mvc.perform(get("/api/retention/funnel").header(AUTH, bearer())
                        .param("workflowId", String.valueOf(wf)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowId").value(wf))
                .andExpect(jsonPath("$.data.seeded").value(3))
                .andExpect(jsonPath("$.data.executed").value(3))
                .andExpect(jsonPath("$.data.reached").value(3))
                .andExpect(jsonPath("$.data.seededToExecutedRate").value(1.0))
                .andExpect(jsonPath("$.data.executedToReachedRate").value(1.0));

        // 租户维度（无 workflowId）同值
        mvc.perform(get("/api/retention/funnel").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seeded").value(3))
                .andExpect(jsonPath("$.data.reached").value(3));
    }

    // ---------- 3. 区间留存：事件窗口活跃 ----------

    @Test
    void intervalRetentionUsesEventWindows() throws Exception {
        long a = createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long b = createContact(contact("B", "13800000002", null, highRisk("王芳")));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // prior=[now-14d, now-7d)：A、B 活跃；current=[now-7d, now)：仅 B 继续活跃 → cohort=2 retained=1
        importEvents(a, "page_view", now.minusSeconds(10 * 86400L));
        importEvents(b, "page_view", now.minusSeconds(10 * 86400L));
        importEvents(b, "page_view", now.minusSeconds(1 * 86400L));

        mvc.perform(get("/api/retention/interval").header(AUTH, bearer()).param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.cohort").value(2))
                .andExpect(jsonPath("$.data.retained").value(1))
                .andExpect(jsonPath("$.data.rate").value(closeTo(0.5, 0.001)));
    }

    // ---------- 4. 渠道效果：送达/失败/去重触达 ----------

    @Test
    void channelEffectAggregatesByChannel() throws Exception {
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
        createContact(contact("B", "13800000002", null, highRisk("王芳")));
        createContact(contact("C", "13800000003", null, highRisk("李静")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveSmokeCanvas("m5-channel", "渠道效果");
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());
        String exec = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(inTenant(() -> deliveryRecordMapper.selectCount(
                Wrappers.<DeliveryRecord>lambdaQuery().eq(DeliveryRecord::getExecutionId, executionIdOf(exec)))))
                .isEqualTo(3L);

        mvc.perform(get("/api/retention/channel-effect").header(AUTH, bearer()).param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels[0].channel").value("sms"))
                .andExpect(jsonPath("$.data.channels[0].total").value(3))
                .andExpect(jsonPath("$.data.channels[0].sent").value(3))
                .andExpect(jsonPath("$.data.channels[0].failed").value(0))
                .andExpect(jsonPath("$.data.channels[0].distinctContacts").value(3))
                .andExpect(jsonPath("$.data.channels[0].deliveryRate").value(1.0));
    }

    // ---------- 5. 工作流效果：触达后留存 ----------

    @Test
    void workflowEffectRetainsReachedContacts() throws Exception {
        long a = createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long b = createContact(contact("B", "13800000002", null, highRisk("王芳")));
        createContact(contact("C", "13800000003", null, highRisk("李静")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveSmokeCanvas("m5-effect", "工作流效果");
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());
        String exec = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        // 触达后 2 人回访（行为事件），1 人沉默
        importEvents(a, "page_view", Instant.now());
        importEvents(b, "page_view", Instant.now());

        mvc.perform(get("/api/retention/workflows").header(AUTH, bearer()).param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflows[0].workflowId").value(wf))
                .andExpect(jsonPath("$.data.workflows[0].reached").value(3))
                .andExpect(jsonPath("$.data.workflows[0].retained").value(2))
                .andExpect(jsonPath("$.data.workflows[0].retentionRate").value(closeTo(2.0 / 3.0, 0.001)));
    }

    // ---------- 6. 流失预警 CHURN Agent：规则批量扫描 + 回写 + 审计 ----------

    @Test
    void churnScanRatesByInactivityAndWritesAttributes() throws Exception {
        // A：从未活跃 → HIGH(90)；B：45 天未活跃(>30) → HIGH(75)；C：2 天未活跃(<=30) → LOW(5)
        long a = createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long b = createContact(contact("B", "13800000002", null, highRisk("王芳")));
        long c = createContact(contact("C", "13800000003", null, highRisk("李静")));
        importEvents(b, "page_view", Instant.now().minusSeconds(45 * 86400L));
        importEvents(c, "page_view", Instant.now().minusSeconds(2 * 86400L));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        assertThat(inTenant(() -> audienceSnapshotMapper.selectById(snapshot)).getMemberCount()).isEqualTo(3);

        mvc.perform(post("/api/agent/churn/scan").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + ",\"inactiveDays\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audienceSnapshotId").value(snapshot))
                .andExpect(jsonPath("$.data.thresholdDays").value(30))
                .andExpect(jsonPath("$.data.scanned").value(3))
                .andExpect(jsonPath("$.data.high").value(2))
                .andExpect(jsonPath("$.data.medium").value(0))
                .andExpect(jsonPath("$.data.low").value(1))
                .andExpect(jsonPath("$.data.updatedAttributes").value(3));

        // churn_risk 回写（jsonb 字符串）：A/B = HIGH（覆盖原 HIGH 标记），C = LOW
        List<String> tiers = churnTiers(a, b, c);
        assertThat(tiers).containsExactly("HIGH", "HIGH", "LOW");

        // 审计：CHURN + churn_scan + 规则版本 + schema 通过
        AgentAudit audit = inTenant(() -> agentAuditMapper.selectList(
                Wrappers.<AgentAudit>lambdaQuery().orderByDesc(AgentAudit::getId).last("limit 1"))).get(0);
        assertThat(audit.getAgentType() + "|" + audit.getAction() + "|" + audit.getStatus() + "|"
                + audit.getSchemaValid() + "|" + audit.getStrategyVersion())
                .isEqualTo("CHURN|churn_scan|SUCCESS|true|rule");
    }

    // ---------- helpers（复用 M2/M3/M4 模式） ----------

    private void importEvents(long contactId, String eventName, Instant occurredAt) throws Exception {
        String body = "{\"events\":[{\"contactId\":" + contactId + ",\"eventName\":\"" + eventName
                + "\",\"occurredAt\":\"" + occurredAt + "\"}]}";
        mvc.perform(post("/api/events").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(1));
    }

    private long executionIdOf(String body) {
        return Long.parseLong(JsonPath.read(body, "$.data.executionId").toString());
    }

    private List<String> churnTiers(Long... ids) {
        // jsonb 字符串读出带引号（如 "HIGH"），按 btrim 语义去引号
        return inTenant(() -> contactAttributeMapper.selectList(
                        Wrappers.<ContactAttribute>lambdaQuery().eq(ContactAttribute::getKey, "churn_risk")
                                .in(ContactAttribute::getContactId, java.util.Arrays.asList(ids))
                                .orderByAsc(ContactAttribute::getContactId)))
                .stream().map(ca -> ca.getValue().replaceAll("^\"|\"$", "")).toList();
    }

    private long saveSmokeCanvas(String name, String description) throws Exception {
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canvasBody(name, description)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private String canvasBody(String name, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("nodes", List.of(
                node("trigger", "TRIGGER", "开始", null),
                node("send", "ACTION", "短信下发", Map.of("channel", "sms", "templateId", 1, "unitCost", 0.05)),
                node("end", "END", "结束", null)));
        m.put("edges", List.of(
                edge("trigger", "send", null),
                edge("send", "end", null)));
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