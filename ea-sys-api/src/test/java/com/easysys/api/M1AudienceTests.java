package com.easysys.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 验收：接触库 CRUD + 属性/标签全方位替换 + 人群 DSL 圈选 + 快照冻结 + 多租户隔离。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M1AudienceTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void login() throws Exception {
        // 测试间数据隔离：业务表全清（tenant/sys_user 种子不动）
        jdbc.update("TRUNCATE contact_tag, contact_attribute, contact, audience_snapshot_member, " +
                "audience_snapshot, audience RESTART IDENTITY CASCADE");
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 接触库 ----------

    @Test
    void contactCrudWithProfileReplace() throws Exception {
        long id = createContact(contactBody("EXT-C1", "13800000001",
                Map.of("churn_risk", "HIGH", "level", 3), List.of("vip", "vvip")));

        // 详情：属性/标签完整回读
        mvc.perform(get("/api/contacts/{id}", id).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalId").value("EXT-C1"))
                .andExpect(jsonPath("$.data.attributes.churn_risk").value("HIGH"))
                .andExpect(jsonPath("$.data.attributes.level").value(3))
                .andExpect(jsonPath("$.data.tags.length()").value(2));

        // 全量替换：新属性/标签替换旧
        putJson("/api/contacts/" + id, contactBody("EXT-C1", "13800000001",
                        Map.of("churn_risk", "LOW"), List.of("silver")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/contacts/{id}", id).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.churn_risk").value("LOW"))
                .andExpect(jsonPath("$.data.attributes.level").doesNotExist())
                .andExpect(jsonPath("$.data.tags[0]").value("silver"));

        // 列表分页
        mvc.perform(get("/api/contacts").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.externalId=='EXT-C1')]").exists());

        // 逻辑删除
        mvc.perform(delete("/api/contacts/{id}", id).header(AUTH, bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/contacts/{id}", id).header(AUTH, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void tenantIsolationBlocksCrossTenantReads() throws Exception {
        // 租户 2 直插数据（绕开 API 租户上下文），验证租户 1 不可见；
        // 显式 id=99999 避开 BIGSERIAL 序列（DevDataInitializer 显式插 id=1 不推进序列）
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, 't2') ON CONFLICT (id) DO NOTHING", 99999L);
        Long t2 = 99999L;
        jdbc.update("INSERT INTO contact (tenant_id, external_id, phone, status) VALUES (?, ?, ?, 'active')",
                t2, "EXT-T2", "13900000000");

        createContact(contactBody("EXT-T1", "13700000000", Map.of(), List.of()));

        String body = mvc.perform(get("/api/contacts?keyword=EXT-").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> extIds = JsonPath.read(body, "$.data.records[*].externalId");
        assertThat(extIds).contains("EXT-T1").doesNotContain("EXT-T2");
    }

    // ---------- 圈选与快照 ----------

    @Test
    void circleNestedRuleSelectsExpectedMembers() throws Exception {
        long a = createContact(contactBody("A", "13800000001",
                Map.of("churn_risk", "HIGH", "level", 3), List.of("vip", "vvip")));
        long b = createContact(contactBody("B", "13800000002",
                Map.of("churn_risk", "LOW"), List.of("vip")));
        long c = createContact(contactBody("C", "13800000003",
                Map.of("churn_risk", "HIGH"), List.of()));
        long d = createContact(contactBody("D", "13800000004",
                Map.of("churn_risk", "HIGH", "level", 5), List.of("vip")));
        createContact(contactBody("E", "13800000005", Map.of(), List.of()));

        // AND [ OR [ phone == E1, churn_risk == HIGH ], tag.vip exists ]
        long audience = createAudience("vip-or-high-risk",
                rule("AND", List.of(
                        rule("OR", List.of(
                                cond("contact.phone", "equals", "13800000001"),
                                        cond("attribute.churn_risk", "equals", "HIGH"))),
                        condNoValue("tag.vip", "exists"))));

        MvcResult r = mvc.perform(post("/api/audiences/{id}/snapshot", audience).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberCount").value(2))
                .andExpect(jsonPath("$.data.status").value("ready"))
                .andExpect(jsonPath("$.data.filterVersion").value(1))
                .andReturn();
        long snapshotId = Long.parseLong(JsonPath.read(r.getResponse().getContentAsString(), "$.data.id").toString());

        Set<Long> members = membersOf(snapshotId);
        assertThat(members).containsExactlyInAnyOrder(a, d);
        assertThat(members).doesNotContain(b, c);
    }

    @Test
    void circleNumericAttributeAndTagNotIn() throws Exception {
        long a = createContact(contactBody("N1", "13800000010", Map.of("level", 3), List.of("vip")));
        long b = createContact(contactBody("N2", "13800000011", Map.of("level", 1), List.of("vip")));
        long c = createContact(contactBody("N3", "13800000012", Map.of("level", 5), List.of()));

        // level > 2（数值属性比较，jsonb #>> '{}'::numeric）
        long aud1 = createAudience("high-level",
                rule("AND", List.of(cond("attribute.level", "gt", 2))));
        long snap1 = circle(aud1);
        assertThat(membersOf(snap1)).containsExactlyInAnyOrder(a, c);

        // 非 vip（tag NOT IN）
        long aud2 = createAudience("non-vip",
                rule("AND", List.of(cond("tag.vip", "not_in", List.of("vip")))));
        long snap2 = circle(aud2);
        assertThat(membersOf(snap2)).containsExactlyInAnyOrder(c);
        assertThat(membersOf(snap2)).doesNotContain(a, b);
    }

    @Test
    void versionFreezesAcrossSnapshots() throws Exception {
        long audience = createAudience("churn-track",
                rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long s1 = circle(audience);

        // 改规则 → version 升为 2；历史快照 filter_version 仍为 1
        long a = createContact(contactBody("V1", "13800000020", Map.of("churn_risk", "HIGH"), List.of()));
        long v1 = circle(audience);
        putJson("/api/audiences/" + audience,
                        audienceBody("churn-track-v2", rule("AND", List.of(cond("attribute.churn_risk", "equals", "LOW")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        long v2 = circle(audience);

        String snaps = mvc.perform(get("/api/audiences/{id}/snapshots", audience).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> versions = JsonPath.read(snaps, "$.data.records[*].filterVersion");
        assertThat(versions).containsExactlyInAnyOrder(1, 1, 2);

        // 第二次圈选(规则 v2, HIGH) 仍命中 v1 —— 快照冻结不受规则变更影响
        assertThat(membersOf(v1)).containsExactly(a);
        assertThat(membersOf(v2)).isEmpty();
    }

    @Test
    void emptyResultFrozenAsReadyZero() throws Exception {
        long audience = createAudience("no-match", rule("AND", List.of(cond("contact.phone", "equals", "19999999999"))));
        long snap = circle(audience);
        String s = mvc.perform(get("/api/snapshots/{id}/members", snap).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<Object> recs = JsonPath.read(s, "$.data.records");
        assertThat(recs).isEmpty();
    }

    // ---------- 失败路径 ----------

    @Test
    void invalidRulesRejectedWith400() throws Exception {
        // 事件字段：M3 后支持
        expectRuleRejected(rule("AND", List.of(cond("event.amount", "gt", 100))), "事件");
        // 未知操作符
        expectRuleRejected(rule("AND", List.of(cond("contact.phone", "bogus", "1"))), "操作符");
        // 未知字段
        expectRuleRejected(rule("AND", List.of(cond("contact.hacker", "equals", "1"))), "字段");
        // 空 items 禁止
        expectRuleRejected("{\"op\":\"AND\",\"items\":[]}", "items");
        // 非法逻辑符
        expectRuleRejected("{\"op\":\"XOR\",\"items\":[]}", "AND");
        // 非法 JSON 结构（缺 items）
        expectRuleRejected("{\"op\":\"AND\"}", "items");
    }

    @Test
    void snapshotOfMissingAudienceOrSnapshotIs404() throws Exception {
        mvc.perform(post("/api/audiences/999999999/snapshot").header(AUTH, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mvc.perform(get("/api/snapshots/999999999/members").header(AUTH, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ---------- helpers ----------

    private String bearer() {
        return "Bearer " + token;
    }

    private long createContact(String body) throws Exception {
        String s = mvc.perform(post("/api/contacts").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private long createAudience(String name, String ruleJson) throws Exception {
        String s = mvc.perform(post("/api/audiences").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(audienceBody(name, ruleJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
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

    private Set<Long> membersOf(long snapshotId) throws Exception {
        String body = mvc.perform(get("/api/snapshots/{id}/members?page=1&size=200", snapshotId).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.data.records[*].contactId");
        return ids.stream().map(Integer::longValue).collect(Collectors.toSet());
    }

    private void expectRuleRejected(String ruleJson, String messageFragment) throws Exception {
        mvc.perform(post("/api/audiences").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(audienceBody("bad", ruleJson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messageFragment)));
    }

    private String contactBody(String externalId, String phone, Map<String, Object> attributes, List<String> tags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalId", externalId);
        m.put("phone", phone);
        m.put("attributes", attributes);
        m.put("tags", tags);
        return asJson(m);
    }

    private String audienceBody(String name, String ruleJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        // rule 以字符串形式嵌入（服务端 JsonNode 校验）
        m.put("rule", "PLACEHOLDER");
        String base = asJson(m);
        return base.replace("\"PLACEHOLDER\"", ruleJson);
    }

    private String asJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String rule(String op, List<String> items) {
        // items 各元素是合法 JSON 对象片段，raw 拼接为数组（List.toString 会加引号污染）
        return "{\"op\":\"" + op + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    private String cond(String field, String op, Object value) {
        return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + asJson(value) + "}";
    }

    /** exists/not_exists 等无值条件。 */
    private String condNoValue(String field, String op) {
        return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String path, String body) throws Exception {
        return mvc.perform(put(path).header(AUTH, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}