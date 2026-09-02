package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.mapper.WorkflowMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 验收：画布保存 → 结构校验 → 发布 → 干跑报告（DAG 工作流引擎）。
 * 链路断言：版本化草稿/发布、条件分流人数、execution 重查报告一致。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M2WorkflowTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowMapper workflowMapper;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void login() throws Exception {
        inTenant(workflowMapper::testTruncateAll);
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 主链路 ----------

    @Test
    void saveValidatePublishDryRunReportFullLifecycle() throws Exception {
        long a = createContact(contactBody("A", "13800000001",
                Map.of("churn_risk", "HIGH", "level", 3), List.of("vip")));
        long c = createContact(contactBody("C", "13800000003",
                Map.of("churn_risk", "HIGH", "level", 5), List.of("vip")));
        createContact(contactBody("B", "13800000002", Map.of("churn_risk", "LOW"), List.of()));

        // 圈 HIGH：A、C 入快照（≥2 成员触发不同属性值）
        long audience = createAudience("high-risk",
                rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        long snapshot = circle(audience);
        assertThat(membersOf(snapshot)).containsExactlyInAnyOrder(a, c);

        // 1) 保存画布 → v1 DRAFT，节点/边回显
        String body = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canvasBody("churn-push", "回归关怀")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.nodes.length()").value(5))
                .andExpect(jsonPath("$.data.edges.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        long workflowId = Long.parseLong(JsonPath.read(body, "$.data.id").toString());

        // 2) 校验 → valid
        mvc.perform(post("/api/workflows/{id}/validate", workflowId).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.errors.length()").value(0));

        // 3) 发布 → published
        mvc.perform(post("/api/workflows/{id}/publish", workflowId).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("published"))
                .andExpect(jsonPath("$.data.publishedAt").exists());

        // 4) 干跑 → 成功 + 各节点人数
        MvcResult dr = mvc.perform(post("/api/workflows/{id}/dry-run", workflowId).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.error").doesNotExist())
                .andExpect(jsonPath("$.data.totalMembers").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='trigger')].contacts").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='cond')].contacts").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].contacts").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='push')].contacts").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='end')].contacts").value(2))
                .andReturn();
        String drBody = dr.getResponse().getContentAsString();
        long executionId = Long.parseLong(
                JsonPath.read(drBody, "$.data.executionId").toString());
        assertThat(executionId).isPositive();

        // 5) 报告重查：人数与干跑一致
        mvc.perform(get("/api/workflows/executions/{id}/report", executionId).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionId").value(executionId))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.totalMembers").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].contacts").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='push')].contacts").value(0));
    }

    // ---------- 条件分流 ----------

    @Test
    void conditionRoutingSplitsMembersByBranch() throws Exception {
        createContact(contactBody("A", "13800000001", Map.of("churn_risk", "HIGH"), List.of()));
        createContact(contactBody("B", "13800000002", Map.of("churn_risk", "LOW"), List.of()));

        // 所有带 churn_risk 的成员都进快照，dry-run 内部分流
        long audience = createAudience("has-risk",
                rule("AND", List.of(condNoValue("attribute.churn_risk", "exists"))));
        long snapshot = circle(audience);
        assertThat(membersOf(snapshot)).hasSize(2);

        long wf = saveCanvas();
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workflows/{id}/dry-run", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalMembers").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='sms')].contacts").value(1))
                .andExpect(jsonPath("$.data.nodes[?(@.key=='push')].contacts").value(1));
    }

    // ---------- 版本化 ----------

    @Test
    void percentageConditionSplitsMembersDeterministically() throws Exception {
        createContact(contactBody("P1", "13800001001", Map.of("bucket", "x"), List.of()));
        createContact(contactBody("P2", "13800001002", Map.of("bucket", "x"), List.of()));

        long audience = createAudience("all",
                rule("AND", List.of(condNoValue("attribute.bucket", "exists"))));
        long snapshot = circle(audience);
        assertThat(membersOf(snapshot)).hasSize(2);

        // 条件边 percentage=50（按 contact.id 稳定哈希分流），兜底 else → push
        long wf = savePctCanvas(50);
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        // 两次干跑分流人数一致（确定性），且不丢人：sms + push = 2
        String r1 = dryRunBody(wf, snapshot);
        String r2 = dryRunBody(wf, snapshot);
        int sms1 = nodeContacts(r1, "sms");
        int push1 = nodeContacts(r1, "push");
        int sms2 = nodeContacts(r2, "sms");
        int push2 = nodeContacts(r2, "push");
        assertThat(sms1 + push1).isEqualTo(2);
        assertThat(sms2 + push2).isEqualTo(2);
        assertThat(sms1).isEqualTo(sms2);
        assertThat(push1).isEqualTo(push2);

        // 边界：percentage=0 全走兜底（push），100 全走条件边（sms）
        long wf0 = savePctCanvas(0);
        mvc.perform(post("/api/workflows/{id}/publish", wf0).header(AUTH, bearer()))
                .andExpect(status().isOk());
        String r0 = dryRunBody(wf0, snapshot);
        assertThat(nodeContacts(r0, "sms")).isEqualTo(0);
        assertThat(nodeContacts(r0, "push")).isEqualTo(2);

        long wf100 = savePctCanvas(100);
        mvc.perform(post("/api/workflows/{id}/publish", wf100).header(AUTH, bearer()))
                .andExpect(status().isOk());
        String r100 = dryRunBody(wf100, snapshot);
        assertThat(nodeContacts(r100, "sms")).isEqualTo(2);
        assertThat(nodeContacts(r100, "push")).isEqualTo(0);
    }

    /**
     * 保存校验拦截越界 percentage：编译期拒绝，400 + DSL 非法。
     */
    @Test
    void percentageOutOfRangeRejectedOnSave() throws Exception {
        String bad = "{\"name\":\"pct-bad\",\"nodes\":["
                + "{\"key\":\"t\",\"type\":\"TRIGGER\"},"
                + "{\"key\":\"c\",\"type\":\"CONDITION\"},"
                + "{\"key\":\"a\",\"type\":\"ACTION\"},"
                + "{\"key\":\"e\",\"type\":\"END\"}],"
                + "\"edges\":["
                + "{\"source\":\"t\",\"target\":\"c\"},"
                + "{\"source\":\"c\",\"target\":\"a\",\"condition\":{\"op\":\"AND\",\"items\":[{\"field\":\"contact.id\",\"op\":\"percentage\",\"value\":150}]}},"
                + "{\"source\":\"a\",\"target\":\"e\"}]}";
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("DSL 非法")));
    }

    // ---------- 版本化 ----------

    @Test
    void publishedSaveCreatesNewDraftVersionAndArchivesOldPublished() throws Exception {
        long wf = saveCanvas();
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        // PUBLISHED 后保存 → v2 DRAFT（旧 published 行保留为 archived）
        mvc.perform(put("/api/workflows/{id}", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canvasBody("churn-push-v2", "迭代二")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.name").value("churn-push-v2"));

        // v2 publish 上线时，旧 published v1 行归档为 archived，published 指针指向 v2
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("published"));

        Workflow archived = inTenant(() -> workflowMapper.selectOne(
                Wrappers.<Workflow>lambdaQuery().eq(Workflow::getRefId, wf).eq(Workflow::getStatus, "archived")));
        assertThat(archived.getVersion()).isEqualTo(1);

        Workflow published = inTenant(() -> workflowMapper.selectOne(
                Wrappers.<Workflow>lambdaQuery().eq(Workflow::getRefId, wf).eq(Workflow::getStatus, "published")));
        assertThat(published.getVersion()).isEqualTo(2);
    }

    // ---------- 失败路径 ----------

    @Test
    void invalidCanvasRejectedWith400() throws Exception {
        // 环画布
        String cycle = "{\"name\":\"cycle\",\"nodes\":["
                + "{\"key\":\"t\",\"type\":\"TRIGGER\"},"
                + "{\"key\":\"a\",\"type\":\"ACTION\"},"
                + "{\"key\":\"e\",\"type\":\"END\"}],"
                + "\"edges\":["
                + "{\"source\":\"t\",\"target\":\"a\"},"
                + "{\"source\":\"a\",\"target\":\"t\"},"
                + "{\"source\":\"a\",\"target\":\"e\"}]}";
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(cycle))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("环")));

        // 非法条件 DSL（未知 op）
        String badCond = "{\"name\":\"bad\",\"nodes\":["
                + "{\"key\":\"t\",\"type\":\"TRIGGER\"},"
                + "{\"key\":\"c\",\"type\":\"CONDITION\"},"
                + "{\"key\":\"a\",\"type\":\"ACTION\"},"
                + "{\"key\":\"e\",\"type\":\"END\"}],"
                + "\"edges\":["
                + "{\"source\":\"t\",\"target\":\"c\"},"
                + "{\"source\":\"c\",\"target\":\"a\",\"condition\":{\"op\":\"AND\",\"items\":[{\"field\":\"contact.x\",\"op\":\"bogus\",\"value\":\"1\"}]}},"
                + "{\"source\":\"a\",\"target\":\"e\"}]}";
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(badCond))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("DSL 非法")));
    }

    @Test
    void dryRunRejectsUnpublishedAndMissingSnapshot() throws Exception {
        long wf = saveCanvas();
        long audience = createAudience("any",
                rule("AND", List.of(condNoValue("attribute.churn_risk", "exists"))));
        long snapshot = circle(audience);

        // 未发布干跑 → 400
        mvc.perform(post("/api/workflows/{id}/dry-run", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("请先发布")));

        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk());

        // 快照不存在 → 404
        mvc.perform(post("/api/workflows/{id}/dry-run", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":999999999}"))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    /** TRIGGER → CONDITION(percentage 条件边 → sms / 兜底 → push) → END 画布。 */
    private long savePctCanvas(int pct) throws Exception {
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pctCanvasBody(pct)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private String pctCanvasBody(int pct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "ab-split-" + pct);
        m.put("description", "AB 分流");
        m.put("nodes", List.of(
                node("trigger", "TRIGGER", "开始", null),
                node("cond", "CONDITION", "百分比分流", null),
                node("sms", "ACTION", "短信",
                        Map.of("channel", "sms", "templateId", 1, "unitCost", 0.05)),
                node("push", "ACTION", "推送",
                        Map.of("channel", "push", "templateId", 2)),
                node("end", "END", "结束", null)));
        m.put("edges", List.of(
                edge("trigger", "cond", null),
                edge("cond", "sms", "{\"op\":\"AND\",\"items\":[{\"field\":\"contact.id\",\"op\":\"percentage\",\"value\":" + pct + "}]}"),
                edge("cond", "push", null),
                edge("sms", "end", null),
                edge("push", "end", null)));
        return asJson(m);
    }

    private String dryRunBody(long wf, long snapshot) throws Exception {
        return mvc.perform(post("/api/workflows/{id}/dry-run", wf).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audienceSnapshotId\":" + snapshot + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int nodeContacts(String body, String key) throws Exception {
        Object v = JsonPath.read(body, "$.data.nodes[?(@.key=='" + key + "')].contacts");
        if (v instanceof List<?> list && !list.isEmpty()) {
            return ((Number) list.get(0)).intValue();
        }
        return 0;
    }

    /** TRIGGER → CONDITION(条件边 高流失→sms / 兜底→push) → END 画布。 */
    private long saveCanvas() throws Exception {
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canvasBody("churn-push", "回归关怀")))
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
                node("cond", "CONDITION", "高流失分流", null),
                node("sms", "ACTION", "短信",
                        Map.of("channel", "sms", "templateId", 1, "unitCost", 0.05)),
                node("push", "ACTION", "推送",
                        Map.of("channel", "push", "templateId", 2)),
                node("end", "END", "结束", null)));
        m.put("edges", List.of(
                edge("trigger", "cond", null),
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

    private Set<Long> membersOf(long snapshotId) throws Exception {
        String body = mvc.perform(get("/api/snapshots/{id}/members?page=1&size=200", snapshotId)
                        .header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.data.records[*].contactId");
        return ids.stream().map(Integer::longValue).collect(Collectors.toSet());
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

    private String condNoValue(String field, String op) {
        return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\"}";
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