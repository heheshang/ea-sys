package com.easysys.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 验收：智能体接入（分层/路由 agent，schema 校验 + 确定性降级 + 审计）。
 * 断言：AGENT_SPLIT 按通道可达性分层路由（L1 短信/L2 邮件/L3 双通道/L4 无通道）→ contact_attribute 打标
 * → 无通道成员落无条件兜底边 → dry-run 零落库 → 策略 draft→published 生效版本选择
 * → audit_log（strategy_generate/route_split）→ 路由预览按近 24h 触达史重排。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M4AgentTests {

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
    JdbcTemplate jdbc;

    @Autowired
    RedissonClient redisson;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void login() throws Exception {
        jdbc.update("TRUNCATE delivery_record, template, execution_node_state, execution, " +
                "workflow_edge, workflow_node, workflow, contact_tag, contact_attribute, contact, " +
                "audience_snapshot_member, audience_snapshot, audience, audit_log, layer_strategy " +
                "RESTART IDENTITY CASCADE");
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 1. 分层路由:通道可达性 → L1/L2/L3 分支 + 打标 ----------

    @Test
    void splitRoutesByChannelAvailabilityAndWritesLayerAttributes() throws Exception {
        long a = createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long b = createContact(contact("B", null, "b@example.com", highRisk("王芳")));
        long c = createContact(contact("C", "13800000003", "c@example.com", highRisk("李静")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveCanvas();
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        String body = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.totalMembers").value(3))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.byLayer.L1").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.byLayer.L2").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.byLayer.L3").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.routed.sms1").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.routed.sms2").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.routed.sms3").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.dropped").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms1')].contacts").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms2')].contacts").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms3')].contacts").value(1))
                .andReturn().getResponse().getContentAsString();

        // 每层恰好 1 人：A→sms1, B→sms2, C→sms3（各经 sms 通道下发，每人一行）
        List<String> contacts = jdbc.queryForList(
                "SELECT contact_id::text FROM delivery_record WHERE execution_id = ? ORDER BY contact_id",
                String.class, executionIdOf(body));
        assertThat(contacts).containsExactly(String.valueOf(a), String.valueOf(b), String.valueOf(c));

        // contact_attribute 打标：A=L1 B=L2 C=L3（tenant 隔离内）
        List<String> layers = layerAttributes(a, b, c);
        assertThat(layers).containsExactly("L1", "L2", "L3");
    }

    // ---------- 2. 无通道成员 → 无条件兜底边 ----------

    @Test
    void noChannelMemberFallsBackToElseEdge() throws Exception {
        long d = createContact(contact("D", null, null, highRisk("赵敏")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveCanvas();
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        String body = mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.byLayer.L4").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.dropped").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='end')].contacts").value(1))
                .andReturn().getResponse().getContentAsString();

        // 无通道成员不打任何通道，仅落 layer=L4 标签
        assertThat(layerAttributes(d)).containsExactly("L4");
        Integer delivered = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_record WHERE execution_id = ?",
                Integer.class, Long.parseLong(JsonPath.read(body, "$.data.executionId").toString()));
        assertThat(delivered).isZero();
    }

    // ---------- 3. dry-run 零落库 ----------

    @Test
    void dryRunWritesNoAttributesNoAuditNoDelivery() throws Exception {
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveCanvas();
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workflows/{id}/dry-run", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='split')].output.byLayer.L1").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms1')].contacts").value(1));

        // 画布成员属性（churn_risk 等）由建联系人写入，dry-run 只不允许追加 layer 标签
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact_attribute WHERE key='layer'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_record", Integer.class)).isZero();
    }

    // ---------- 4. 策略 draft → published → 生效版本 ----------

    @Test
    void strategyDraftThenPublishBecomesActiveWithLatestVersion() throws Exception {
        mvc.perform(post("/api/agent/strategies").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"平衡分层\",\"strategyVersion\":\"v2\",\"routeOrder\":[\"email\",\"sms\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.strategyVersion").value("v2"));

        String list = mvc.perform(get("/api/agent/strategies").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(JsonPath.read(list, "$.data[0].id").toString());

        mvc.perform(post("/api/agent/strategies/{id}/publish", id).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("published"));

        // 生效策略 = 最近发布
        mvc.perform(get("/api/agent/strategies/active").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategyVersion").value("v2"));

        // 再发布 v3 → active 切换到 v3
        mvc.perform(post("/api/agent/strategies").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新版分层\",\"strategyVersion\":\"v3\",\"routeOrder\":[\"sms\"]}"))
                .andExpect(status().isOk());
        String list2 = mvc.perform(get("/api/agent/strategies").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id3 = Long.parseLong(JsonPath.read(list2, "$.data[0].id").toString());
        mvc.perform(post("/api/agent/strategies/{id}/publish", id3).header(AUTH, bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agent/strategies/active").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategyVersion").value("v3"));

        // 生成即审计（schema 校验通过）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'strategy_generate' AND schema_valid = true",
                Integer.class)).isEqualTo(2);
    }

    // ---------- 5. route_split 审计 ----------

    @Test
    void routeSplitAuditWrittenOnRealExecute() throws Exception {
        createContact(contact("A", "13800000001", null, highRisk("张伟")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveCanvas();
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE agent_type = 'LAYER' AND action = 'route_split'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
        String row = jdbc.queryForObject(
                "SELECT status || '|' || model || '|' || schema_valid || '|' || strategy_version" +
                        " FROM audit_log WHERE action = 'route_split'", String.class);
        // 未发布策略时引用默认分层版本 'default'，schema 校验通过、deterministic 主路径
        assertThat(row).isEqualTo("SUCCESS|deterministic|true|default");
    }

    // ---------- 6. 路由预览：近 24h 触达史后置 ----------

    @Test
    void routePreviewReordersTouchedChannelToEnd() throws Exception {
        long a = createContact(contact("A", "13800000001", null, highRisk("张伟")));
        // 不入 HIGH 受众 → 无触达史
        long untouched = createContact(contact("E", "13800000005", null, Map.of("name", "周杰")));
        long audience = createAudience("high-risk", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        createTemplate("sms", "短信关怀", "亲爱的${name!}，欢迎回来");
        long wf = saveCanvas();
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workflows/{id}/execute", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk());

        // A 近 24h 已触达 sms → sms 后置：["sms","email"] → ["email","sms"]
        mvc.perform(post("/api/agent/route-preview").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + a + ",\"routeOrder\":[\"sms\",\"email\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.touched[0]").value("sms"))
                .andExpect(jsonPath("$.data.reordered[0]").value("email"))
                .andExpect(jsonPath("$.data.reordered[1]").value("sms"))
                .andExpect(jsonPath("$.data.unchanged").value(false));

        // 未触达成员 → 顺序保持
        mvc.perform(post("/api/agent/route-preview").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + untouched + ",\"routeOrder\":[\"sms\",\"email\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.touched").isEmpty())
                .andExpect(jsonPath("$.data.reordered[0]").value("sms"))
                .andExpect(jsonPath("$.data.reordered[1]").value("email"))
                .andExpect(jsonPath("$.data.unchanged").value(true));
    }

    // ---------- helpers（复用 M2/M3 模式） ----------

    private long executionIdOf(String body) {
        return Long.parseLong(JsonPath.read(body, "$.data.executionId").toString());
    }

    private List<String> layerAttributes(Long... ids) {
        return jdbc.queryForList(
                "SELECT btrim(value::text, '\"') FROM contact_attribute WHERE key = 'layer' AND contact_id IN ("
                        + String.join(",", java.util.Arrays.stream(ids).map(String::valueOf).toList())
                        + ") ORDER BY contact_id", String.class);
    }

    private long saveCanvas() throws Exception {
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canvasBody("m4-split", "分层分流")))
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
                node("split", "AGENT_SPLIT", "分层分流", null),
                node("sms1", "ACTION", "短信-L1", Map.of("channel", "sms", "templateId", 1, "unitCost", 0.05)),
                node("sms2", "ACTION", "短信-L2", Map.of("channel", "sms", "templateId", 1, "unitCost", 0.05)),
                node("sms3", "ACTION", "短信-L3", Map.of("channel", "sms", "templateId", 1, "unitCost", 0.05)),
                node("end", "END", "结束", null)));
        m.put("edges", List.of(
                edge("trigger", "split", null),
                edge("split", "sms1", "{\"op\":\"AND\",\"items\":[{\"op\":\"equals\",\"field\":\"contact.layer\",\"value\":\"L1\"}]}"),
                edge("split", "sms2", "{\"op\":\"AND\",\"items\":[{\"op\":\"equals\",\"field\":\"contact.layer\",\"value\":\"L2\"}]}"),
                edge("split", "sms3", "{\"op\":\"AND\",\"items\":[{\"op\":\"equals\",\"field\":\"contact.layer\",\"value\":\"L3\"}]}"),
                edge("split", "end", null),
                edge("sms1", "end", null),
                edge("sms2", "end", null),
                edge("sms3", "end", null)));
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
}