package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.mapper.WorkflowMapper;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 扩展：人工复评（P3/S3,S4）——submit/list/delete/calibration 闭环。
 * 契约：同 (reportId, caseSeq, metric) 二次提交覆盖更新；caseSeq 越界 / score 越界 / verdict
 * 非法 → 400；报告不存在/跨租户 → 404；autoScore=true 时任务报告逐样本自动分非 null、
 * 同步 run 报告 auto null；calibration 仅审计口径（不新增 evaluation_run 审计）。
 * 环境 LLM 未启用：逐样本 reason/round_scores 为空属合法，只断言结构。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M8EvalHumanReviewTests {

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

    @Autowired
    AgentAuditMapper agentAuditMapper;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void login() throws Exception {
        inTenantRun(workflowMapper::testTruncateAll);
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 1. 任务报告：提交 / upsert / 校验 / 删除 / 校准 ----------

    @Test
    void taskReportReviewLifecycleUpsertValidationAndCalibration() throws Exception {
        long ds = createDataset("人工复评任务", "openjudge");
        addCase(ds, "42 的数值是？", 42, "42");
        addCase(ds, "100 加 0 的数值是？", 100, "100");
        addCase(ds, "50 的数值是？", 50, "51");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", List.of("number_accuracy", "string_exact"));
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());
        JsonNode task = waitTask(taskId, "COMPLETED");
        long reportId = task.path("reportId").asLong(-1);
        assertThat(reportId).isGreaterThan(0);

        // 提交：verdict 缺省由 score 派生（0.9 → PASS），reviewer = admin
        JsonNode r1 = submit(reportId, 1, "*", 0.9, null);
        double reviewId1 = r1.path("id").asDouble();
        assertThat(r1.path("score").asDouble()).isCloseTo(0.9, within(0.001));
        assertThat(r1.path("verdict").asText()).isEqualTo("PASS");
        assertThat(r1.path("reviewer").asText()).isEqualTo("admin");

        // 同组二次提交 → 覆盖更新（列表仍 1 条，score 0.7 → WARN）
        submit(reportId, 1, "*", 0.7, "改判");
        submit(reportId, 2, "string_exact", 0.5, "待复核");
        JsonNode list = listReviews(reportId, true);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("caseSeq").asInt()).isEqualTo(1);
        assertThat(list.get(0).path("score").asDouble()).isCloseTo(0.7, within(0.001));
        assertThat(list.get(0).path("verdict").asText()).isEqualTo("WARN");
        assertThat(list.get(0).path("note").asText()).isEqualTo("改判");
        // 任务报告逐样本存在 → auto 非 null（'*' = 适用指标整例均值）
        assertThat(list.get(0).path("auto").isNumber()).isTrue();
        assertThat(list.get(1).path("caseSeq").asInt()).isEqualTo(2);
        assertThat(list.get(1).path("metric").asText()).isEqualTo("string_exact");
        assertThat(list.get(1).path("auto").isNumber()).isTrue();

        // 校验 400：caseSeq=0 / caseSeq>totalCases / score 越界 / verdict 非法
        mvc.perform(post("/api/evaluations/reports/" + reportId + "/reviews")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(Map.of("caseSeq", 0, "score", 0.5))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/evaluations/reports/" + reportId + "/reviews")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(Map.of("caseSeq", 4, "score", 0.5))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/evaluations/reports/" + reportId + "/reviews")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(Map.of("caseSeq", 1, "score", 1.5))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/evaluations/reports/" + reportId + "/reviews")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(Map.of("caseSeq", 1, "score", 0.5, "verdict", "MAYBE"))))
                .andExpect(status().isBadRequest());

        // 跨租户/不存在报告 → 404
        mvc.perform(post("/api/evaluations/reports/999999/reviews")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(Map.of("caseSeq", 1, "score", 0.5))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/evaluations/reviews/999999").header(AUTH, bearer()))
                .andExpect(status().isNotFound());

        // 复评不存在报告（listReviews 守卫）→ 404
        mvc.perform(get("/api/evaluations/reports/999999/reviews").header(AUTH, bearer()))
                .andExpect(status().isNotFound());

        // 删除单条（id 取当前列表；首次 submit 响应 id 已被 upsert 覆盖软删）→ 列表收敛为空；审计计数契约
        long first = list.get(0).path("id").asLong();
        long second = list.get(1).path("id").asLong();
        mvc.perform(delete("/api/evaluations/reviews/" + first).header(AUTH, bearer()))
                .andExpect(status().isOk());
        assertThat(listReviews(reportId, false).size()).isEqualTo(1);
        mvc.perform(delete("/api/evaluations/reviews/" + second).header(AUTH, bearer()))
                .andExpect(status().isOk());
        assertThat(listReviews(reportId, false).size()).isZero();
        assertThat(auditCount("EVALUATION_REVIEW_SUBMIT")).isEqualTo(3);
        assertThat(auditCount("EVALUATION_REVIEW_DELETE")).isEqualTo(2);

        // 校准（重提交 3 条 '*' 黄金标准 + 2 条 string_exact 对比行）
        submit(reportId, 1, "*", 0.9, null);
        submit(reportId, 2, "*", 0.7, null);
        submit(reportId, 3, "*", 0.5, null);
        submit(reportId, 3, "string_exact", 1.0, "层外人工改为通过");
        submit(reportId, 1, "string_exact", 1.0, "一致");
        JsonNode cal = parse(getJson("/api/evaluations/reports/" + reportId + "/reviews/calibration")).path("data");
        assertThat(cal.path("overall").path("n").asInt()).isEqualTo(3);
        assertThat(cal.path("overall").path("meanHuman").asDouble()).isCloseTo(0.7, within(0.001));
        assertThat(cal.path("overall").path("passRate").asDouble()).isCloseTo(0.3333, within(0.001));
        // per-metric：string_exact 两行（case3 auto 0.0 vs human 1.0；case1 auto 1.0 vs human 1.0）
        JsonNode metricRow = null;
        for (JsonNode m : cal.path("metrics")) {
            if ("string_exact".equals(m.path("metric").asText())) {
                metricRow = m;
            }
        }
        assertThat(metricRow).as("校准应含 string_exact 逐指标行").isNotNull();
        assertThat(metricRow.path("meanAuto").asDouble()).isCloseTo(0.5, within(0.001));
        assertThat(metricRow.path("meanHuman").asDouble()).isCloseTo(1.0, within(0.001));
        assertThat(metricRow.path("meanAbsDiff").asDouble()).isCloseTo(0.5, within(0.001));
        assertThat(metricRow.path("agreementRate").asDouble()).isCloseTo(0.5, within(0.001));
        assertThat(metricRow.path("topDeltas").size()).isEqualTo(2);
        assertThat(metricRow.path("topDeltas").get(0).path("delta").asDouble())
                .isCloseTo(-1.0, within(0.001));
        assertThat(metricRow.path("topDeltas").get(1).path("delta").asDouble())
                .isCloseTo(0.0, within(0.001));
        // 校准不写审计（只审计 submit/delete）
        assertThat(auditCount("EVALUATION_REVIEW_SUBMIT")).isEqualTo(8);
        assertThat(auditCount("EVALUATION_REVIEW_DELETE")).isEqualTo(2);
    }

    // ---------- 2. 同步 run 报告：auto 恒定 null + 整体校准 ----------

    @Test
    void syncRunReportReviewHasNullAutoAndOverallCalibration() throws Exception {
        long ds = createDataset("人工复评同步", "openjudge");
        addCase(ds, "1 的数值是？", 1, "1");
        long reportId = run(ds, List.of("string_exact"), null).path("id").asLong();

        submit(reportId, 1, "*", 0.8, null);
        JsonNode list = listReviews(reportId, true);
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("auto").isNull())
                .as("同步 run 报告无逐样本 → auto 恒 null")
                .isTrue();

        // 整体校准可用（无逐指标行）
        JsonNode cal = parse(getJson("/api/evaluations/reports/" + reportId + "/reviews/calibration")).path("data");
        assertThat(cal.path("overall").path("n").asInt()).isEqualTo(1);
        assertThat(cal.path("overall").path("meanHuman").asDouble()).isCloseTo(0.8, within(0.001));
        assertThat(cal.path("overall").path("passRate").asDouble()).isCloseTo(1.0, within(0.001));
        assertThat(cal.path("metrics").size()).isZero();
    }

    // ---------- helpers ----------

    private JsonNode submit(long reportId, int caseSeq, String metric, double score, String note)
            throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseSeq", caseSeq);
        m.put("metric", metric);
        m.put("score", score);
        if (note != null) {
            m.put("note", note);
        }
        return parse(postJson("/api/evaluations/reports/" + reportId + "/reviews", asJson(m))).path("data");
    }

    private JsonNode listReviews(long reportId, boolean autoScore) throws Exception {
        String path = "/api/evaluations/reports/" + reportId + "/reviews";
        if (autoScore) {
            path += "?autoScore=true";
        }
        return parse(getJson(path)).path("data");
    }

    private long createDataset(String name, String mode) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("scope", "llm_call");
        m.put("mode", mode);
        String body = postJson("/api/evaluations/datasets", asJson(m));
        return Long.parseLong(JsonPath.read(body, "$.data.id").toString());
    }

    private long addCase(long datasetId, String question, Object expectedOutput, String providedResponse)
            throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question", question);
        m.put("expectedOutput", expectedOutput);
        m.put("providedResponse", providedResponse);
        String body = postJson("/api/evaluations/datasets/" + datasetId + "/cases", asJson(m));
        return Long.parseLong(JsonPath.read(body, "$.data.id").toString());
    }

    private JsonNode run(long ds, List<String> evaluators, Long versionId) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", evaluators);
        if (versionId != null) {
            req.put("datasetVersionId", versionId);
        }
        return parse(postJson("/api/evaluations/run", asJson(req))).path("data");
    }

    /** 任务详情 data 节点的 {task, metrics}。 */
    private JsonNode taskDetail(long id) throws Exception {
        return parse(getJson("/api/evaluations/tasks/" + id)).path("data");
    }

    /** 轮询至指定终态（上限 15s）；返回终态任务节点。 */
    private JsonNode waitTask(long id, String terminal) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode task = null;
        do {
            task = taskDetail(id).path("task");
            if (terminal.equals(task.path("status").asText())) {
                return task;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        throw new IllegalStateException("任务未在 15s 内到达 " + terminal + "，当前: " + task);
    }

    private String getJson(String path) throws Exception {
        return mvc.perform(get(path).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 任务创建：202 Accepted（与 postJson 的 isOk 断言不同）。 */
    private String postTask(String path, String body) throws Exception {
        return mvc.perform(post(path).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private long auditCount(String action) {
        return inTenant(() -> agentAuditMapper.selectCount(
                Wrappers.<AgentAudit>lambdaQuery().eq(AgentAudit::getAction, action)));
    }

    private JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String asJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private <T> T inTenant(Supplier<T> action) {
        TenantContext.set(new TenantInfo(1L));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenantRun(Runnable action) {
        TenantContext.set(new TenantInfo(1L));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }
}