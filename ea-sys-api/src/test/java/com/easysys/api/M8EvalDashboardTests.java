package com.easysys.api;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 扩展：评测驾驶舱（P3/S5）——dashboard 聚合、compare 分层 + topDegradedSamples、
 * rerun 复现、目录 17 内置指标。环境 LLM 未启用：usage 列恒空 → 各 usage 键为 null、
 * cost/tokens 为 0 属合法。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M8EvalDashboardTests {

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

    // ---------- 1. 驾驶舱：分层 / 趋势 / 指标 / 回归 / 成本 ----------

    @Test
    void dashboardAggregatesLayeringTrendMetricsRegressionsAndCost() throws Exception {
        // 空租户聚合：三层 null、趋势空、其余 null
        JsonNode empty = dashboard(null);
        assertThat(empty.path("layering").isNull()).isTrue();
        assertThat(empty.path("trend").isArray()).isTrue();
        assertThat(empty.path("trend").size()).isZero();
        assertThat(empty.path("metrics").isNull()).isTrue();
        assertThat(empty.path("regressions").isNull()).isTrue();
        assertThat(empty.path("costLatency").isNull()).isTrue();

        long ds = createDataset("驾驶舱", "openjudge");
        addCase(ds, "1 的数值是？", 1, "1");
        addCase(ds, "2 的数值是？", 2, "2");

        // 两次运行 → 趋势升序、最新值与报告视图一致
        JsonNode report1 = run(ds, List.of("task_success", "string_exact"), null);
        JsonNode report2 = run(ds, List.of("task_success", "string_exact"), null);

        JsonNode dash = dashboard(ds);
        // layering：basic 2 例全测全过
        JsonNode basic = dash.path("layering").path("basic");
        assertThat(basic.path("count").asInt()).isEqualTo(2);
        assertThat(basic.path("tested").asInt()).isEqualTo(2);
        assertThat(basic.path("pass_rate").asDouble()).isEqualTo(1.0);
        // trend：升序，末条 = 最新报告
        JsonNode trend = dash.path("trend");
        assertThat(trend.size()).isEqualTo(2);
        assertThat(trend.get(0).path("id").asLong()).isEqualTo(report1.path("id").asLong());
        assertThat(trend.get(1).path("id").asLong()).isEqualTo(report2.path("id").asLong());
        assertThat(trend.get(0).path("summary").path("score").asDouble())
                .isEqualTo(report1.path("summary").path("score").asDouble());
        // 报告 summary 含派生三键
        assertThat(report1.path("summary").path("recommendation").path("verdict").asText()).isEqualTo("GO");
        assertThat(report1.path("summary").path("top_regressions").path("metrics").isArray()).isTrue();
        assertThat(report1.path("summary").path("layering").path("basic").path("count").asInt())
                .isEqualTo(2);
        // 指标：task_success 2 点、latest 1.0、delta 0.0；decision_accuracy 无适用 → latest null
        JsonNode metrics = dash.path("metrics");
        assertThat(metrics.path("latest").path("task_success").asDouble()).isEqualTo(1.0);
        assertThat(metrics.path("delta").path("task_success").asDouble()).isEqualTo(0.0);
        assertThat(metrics.path("latest").path("decision_accuracy").isNull())
                .as("openjudge 无轨迹 → decision_accuracy 无适用，latest 为 null")
                .isTrue();
        JsonNode series = seriesOf(metrics, "task_success");
        assertThat(series.size()).isEqualTo(2);
        assertThat(series.get(0).path("reportId").asLong()).isEqualTo(report1.path("id").asLong());
        // 回归：核心指标连续 delta → 至少 1 行（delta 0）
        JsonNode regressions = dash.path("regressions");
        assertThat(regressions.isArray()).isTrue();
        assertThat(regressions.size()).isGreaterThan(0);
        assertThat(regressions.get(0).path("metric").asText()).isNotBlank();
        assertThat(regressions.get(0).path("current").isNumber()).isTrue();
        assertThat(regressions.get(0).path("previous").isNumber()).isTrue();
        // 成本延迟：LLM 关闭 → usage/token 归零、openjudge 无延迟 → null
        JsonNode cost = dash.path("costLatency");
        assertThat(cost.path("avg_latency_ms").isNull()).isTrue();
        assertThat(cost.path("p95_latency_ms").isNull()).isTrue();
        assertThat(cost.path("avg_steps").isNull()).isTrue();
        assertThat(cost.path("total_tokens").asLong()).isZero();
        assertThat(cost.path("cost_cny").asDouble()).isEqualTo(0.0);
    }

    // ---------- 2. 对比：分层过滤 + topDegradedSamples ----------

    @Test
    void compareFiltersByLayerAndExposesTopDegradedSamples() throws Exception {
        long ds = createDataset("对比层", "openjudge");
        addCase(ds, "1 的数值是？", 1, "1");
        addCase(ds, "2 的数值是？", 2, "2");
        addCase(ds, "3 的数值是？", 3, "3");

        long report1 = taskReport(ds);
        long report2 = taskReport(ds);

        // 无 layer：topDegradedSamples 亦可用（双报告都有逐样本）
        JsonNode plain = compare(report2, report1, null);
        assertThat(plain.path("topDegradedSamples").isArray()).isTrue();
        assertThat(plain.path("topDegradedSamples").size()).isEqualTo(3);
        assertThat(plain.path("topDegradedSamples").get(0).path("caseSeq").asInt()).isPositive();
        assertThat(plain.path("topDegradedSamples").get(0).path("delta").isNumber()).isTrue();

        // layer=basic：双报告同层 seq 交集逐样本 → 层内行 + 回显
        JsonNode basic = compare(report2, report1, "basic");
        assertThat(basic.path("layer").asText()).isEqualTo("basic");
        assertThat(basic.path("metrics").isArray()).isTrue();
        JsonNode firstRow = null;
        for (JsonNode m : basic.path("metrics")) {
            if ("string_exact".equals(m.path("metric").asText())) {
                firstRow = m;
            }
        }
        assertThat(firstRow).as("basic 层内应有 string_exact 行").isNotNull();
        // CompareMetric.category = 指标类别（rule/llm_judge），与 task 契约一致；分层只影响行集与 layer 回显
        assertThat(firstRow.path("category").asText()).isEqualTo("rule");
        assertThat(firstRow.path("current").isNumber()).isTrue();
        assertThat(firstRow.path("baseline").isNumber()).isTrue();
        assertThat(firstRow.path("delta").isNumber()).isTrue();
        assertThat(basic.path("topDegradedSamples").size()).isEqualTo(3);

        // 非法 layer → 400
        mvc.perform(get("/api/evaluations/reports/" + report2 + "/compare?baseline=" + report1
                        + "&layer=foo").header(AUTH, bearer()))
                .andExpect(status().isBadRequest());
    }

    // ---------- 3. 目录 17 内置指标 + rerun 复现 ----------

    @Test
    void catalogHasSeventeenMetricsAndRerunRebindsBaseline() throws Exception {
        // 目录：17 = 规则 11（含 decision_accuracy）+ LLM-Judge 6
        JsonNode catalog = parse(getJson("/api/evaluations/catalog")).path("data");
        assertThat(catalog.size()).isEqualTo(17);
        JsonNode decision = null;
        for (JsonNode ev : catalog) {
            if ("decision_accuracy".equals(ev.path("metric").asText())) {
                decision = ev;
            }
        }
        assertThat(decision).as("decision_accuracy 应为内置规则指标").isNotNull();
        assertThat(decision.path("category").asText()).isEqualTo("rule");
        assertThat(decision.path("description").asText()).isNotBlank();

        // rerun：绑定版本 → 新报告 summary.baseline_report_id = 原报告
        long ds = createDataset("复现", "openjudge");
        addCase(ds, "1 的数值是？", 1, "1");
        long v1 = publishVersion(ds).path("id").asLong();
        JsonNode report = run(ds, List.of("string_exact"), v1);
        long reportId = report.path("id").asLong();
        JsonNode rerun = parse(postJson("/api/evaluations/reports/" + reportId + "/rerun",
                "{}")).path("data");
        long newId = rerun.path("id").asLong();
        assertThat(newId).isNotEqualTo(reportId);
        assertThat(rerun.path("summary").path("baseline_report_id").asLong()).isEqualTo(reportId);
        assertThat(rerun.path("summary").path("score").asDouble())
                .isEqualTo(report.path("summary").path("score").asDouble());

        // rerun 守卫：未绑定版本 → 400（requireVersion 路径留待真实删除场景）
        long ds2 = createDataset("复现未绑定", "openjudge");
        addCase(ds2, "1 的数值是？", 1, "1");
        long unbound = run(ds2, List.of("string_exact"), null).path("id").asLong();
        mvc.perform(post("/api/evaluations/reports/" + unbound + "/rerun")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/evaluations/reports/999999/rerun")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    /** 建一个异步任务并等待完成，返回其报告 id。 */
    private long taskReport(long ds) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", List.of("string_exact", "number_accuracy"));
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());
        JsonNode task = waitTask(taskId, "COMPLETED");
        long reportId = task.path("reportId").asLong(-1);
        if (reportId <= 0) {
            throw new IllegalStateException("任务完成但无 reportId: " + task);
        }
        return reportId;
    }

    private JsonNode dashboard(Long ds) throws Exception {
        String path = "/api/evaluations/dashboard";
        if (ds != null) {
            path += "?datasetId=" + ds;
        }
        return parse(getJson(path)).path("data");
    }

    private JsonNode compare(long currentId, long baselineId, String layer) throws Exception {
        String path = "/api/evaluations/reports/" + currentId + "/compare?baseline=" + baselineId;
        if (layer != null) {
            path += "&layer=" + layer;
        }
        return parse(getJson(path)).path("data");
    }

    private JsonNode seriesOf(JsonNode metrics, String metric) {
        for (JsonNode s : metrics.path("series")) {
            if (metric.equals(s.path("metric").asText())) {
                return s.path("points");
            }
        }
        return null;
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

    private JsonNode publishVersion(long ds) throws Exception {
        String body = mvc.perform(post("/api/evaluations/datasets/" + ds + "/versions")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parse(body).path("data");
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

    private JsonNode taskDetail(long id) throws Exception {
        return parse(getJson("/api/evaluations/tasks/" + id)).path("data");
    }

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

    private String postTask(String path, String body) throws Exception {
        return mvc.perform(post(path).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
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