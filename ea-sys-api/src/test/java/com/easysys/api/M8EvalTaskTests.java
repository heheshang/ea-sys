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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 扩展：评测异步任务（H1）状态机 + 逐样本结果（H3/M1）+ 取消竞争、报告对比（H4）
 * 与评测目录（H6）。核心断言：202 Accepted → 轮询 COMPLETED、report_id 非空、
 * progress 单调不降、sample_results 逐样本 metrics、数据集停用 → FAILED、
 * 终态取消 400、compare delta 与缺项 null、catalog 15 内置 + 自定义。
 *
 * <p>测试环境 LLM 未启用（无 apiKey）：LLM 判分返回 null、judge_scores 不注入，
 * 逐样本 reason/round_scores 为空属合法——只断言结构，不断言内容。</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M8EvalTaskTests {

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
        inTenant(workflowMapper::testTruncateAll);
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 1. 任务状态机：PENDING→RUNNING→COMPLETED + 逐样本结果 + 审计 ----------

    @Test
    void asyncTaskRunsToCompletedWithPerSampleResults() throws Exception {
        long ds = createDataset("异步任务", "openjudge");
        addCase(ds, "42 的数值是？", 42, "42");
        addCase(ds, "100 加 0 的数值是？", 100, "100");
        addCase(ds, "50 的数值是？", 50, "51");

        // 202 Accepted + PENDING（响应视图来自落库行，不受执行线程影响）
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", List.of("number_accuracy", "string_exact"));
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());
        JsonNode pending = taskDetail(taskId).path("task");
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("progressPct").asDouble()).isZero();

        // 轮询至 COMPLETED，progress 单调不降（含终态 100）
        List<Double> progress = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode task = null;
        do {
            task = taskDetail(taskId).path("task");
            double p = task.path("progressPct").asDouble();
            if (progress.isEmpty() || p >= progress.get(progress.size() - 1)) {
                progress.add(p);
            }
            if ("COMPLETED".equals(task.path("status").asText())) {
                break;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        assertThat(task.path("status").asText())
                .as("任务应在 15s 内 COMPLETED（实际 %s）", task.path("status").asText())
                .isEqualTo("COMPLETED");
        for (int i = 1; i < progress.size(); i++) {
            assertThat(progress.get(i)).isGreaterThanOrEqualTo(progress.get(i - 1));
        }
        assertThat(progress.get(progress.size() - 1)).isEqualTo(100.0);

        // 状态机字段：total=3 tested=3、reportId 非空
        assertThat(task.path("totalCases").asInt()).isEqualTo(3);
        assertThat(task.path("testedCases").asInt()).isEqualTo(3);
        long reportId = task.path("reportId").asLong(-1);
        assertThat(reportId).isGreaterThan(0);

        // 逐样本结果：3 条，每条含 metrics[]，number_accuracy 行有 score/passed/applicable 口径
        JsonNode samples = task.path("sampleResults");
        assertThat(samples.isArray()).isTrue();
        assertThat(samples.size()).isEqualTo(3);
        JsonNode carrying = null;
        for (JsonNode s : samples) {
            assertThat(s.path("metrics").isArray()).isTrue();
            assertThat(s.path("metrics").size()).isGreaterThan(0);
            for (JsonNode m : s.path("metrics")) {
                if ("number_accuracy".equals(m.path("metric").asText())) {
                    carrying = m;
                }
            }
        }
        assertThat(carrying).as("逐样本应含 number_accuracy 指标行").isNotNull();
        assertThat(carrying.path("score").asDouble(-1)).isBetween(0.0, 1.0);
        assertThat(carrying.path("passed").isBoolean()).isTrue();

        // 详情 breakdown：从 sample_results 聚合回均值/通过数/适用数
        JsonNode breakdown = taskDetail(taskId).path("metrics");
        JsonNode row = null;
        for (JsonNode m : breakdown) {
            if ("number_accuracy".equals(m.path("metric").asText())) {
                row = m;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("avgScore").asDouble()).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(row.path("passedCount").asInt()).isEqualTo(2);
        assertThat(row.path("applicableCount").asInt()).isEqualTo(3);

        // 权威报告与同步 run 同口径：2/3 + 2/3 平均 66.7 WARN；trace 关联 audit
        JsonNode report = parse(getJson("/api/evaluations/reports/" + reportId)).path("data");
        assertThat(report.path("summary").path("score").asDouble()).isCloseTo(66.7, within(0.1));
        assertThat(report.path("summary").path("verdict").asText()).isEqualTo("WARN");
        assertThat(report.path("traceId").asText()).startsWith("eval-");

        // 审计：dataset 1 + case 3 + task create 1 + run 1 = 6；run 审计为最后一条（M8 口径）
        assertThat(auditCount()).isEqualTo(6);
        assertThat(lastAuditLine()).isEqualTo("EVALUATION|evaluation_run|SUCCESS|true|rule");

        // 终态取消 → 400（任务已完成，不允许取消）
        mvc.perform(post("/api/evaluations/tasks/" + taskId + "/cancel").header(AUTH, bearer()))
                .andExpect(status().isBadRequest());
    }

    // ---------- 2. 数据集停用 → 任务 FAILED（任务级失败语义） ----------

    @Test
    void asyncTaskFailsWhenDatasetDisabled() throws Exception {
        long ds = createDataset("停用任务", "openjudge");
        addCase(ds, "q", "期望", "响应");

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("name", "停用任务");
        upd.put("scope", "llm_call");
        upd.put("mode", "openjudge");
        upd.put("status", "DISABLED");
        putJson("/api/evaluations/datasets/" + ds, asJson(upd));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", List.of("string_exact"));
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());

        JsonNode task = waitTask(taskId, "FAILED");
        assertThat(task.path("errorMessage").asText()).contains("停用");
        assertThat(task.path("reportId").isNull()).isTrue();

        // 失败终态同样不可取消
        mvc.perform(post("/api/evaluations/tasks/" + taskId + "/cancel").header(AUTH, bearer()))
                .andExpect(status().isBadRequest());
    }

    // ---------- 3. RUNNING 取消：CANCELING → CANCELED，报告不回滚残留 ----------

    @Test
    void asyncTaskCancelWhileRunningEndsCanceledWithoutReport() throws Exception {
        long ds = createDataset("取消任务", "openjudge");
        // 80 例批量导入，确保执行窗口充裕、可观察 RUNNING 中间态
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            jsonl.append("{\"question\":\"q").append(i).append("\",\"reference\":\"")
                    .append(i).append("\",\"response\":\"").append(i).append("\"}\n");
        }
        mvc.perform(post("/api/evaluations/datasets/" + ds + "/import")
                        .contentType(MediaType.TEXT_PLAIN).content(jsonl.toString())
                        .header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", List.of("number_accuracy"));
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());

        // 等待进入 RUNNING 且已有部分进度（保证取消落在执行中而非终态）
        JsonNode running = waitRunningWithProgress(taskId, 80);
        assertThat(running.path("status").asText()).isEqualTo("RUNNING");
        assertThat(running.path("testedCases").asInt()).isLessThan(80);

        // 取消 → 200；最终 CANCELED、无报告残留
        mvc.perform(post("/api/evaluations/tasks/" + taskId + "/cancel").header(AUTH, bearer()))
                .andExpect(status().isOk());
        JsonNode task = waitTask(taskId, "CANCELED");
        assertThat(task.path("reportId").isNull()).isTrue();
        assertThat(task.path("errorMessage").asText()).contains("取消");

        // 终态（CANCELED）再次取消 → 400
        mvc.perform(post("/api/evaluations/tasks/" + taskId + "/cancel").header(AUTH, bearer()))
                .andExpect(status().isBadRequest());
    }

    // ---------- 4. 报告对比：delta 对齐 + 缺项 null + baseline 必填 ----------

    @Test
    void compareReportsAlignsMetricsAndNullsMissing() throws Exception {
        long ds = createDataset("对比", "openjudge");
        addCase(ds, "42 的数值是？", 42, "42");
        addCase(ds, "100 加 0 的数值是？", 100, "100");
        addCase(ds, "50 的数值是？", 50, "51");

        Map<String, Object> runA = new LinkedHashMap<>();
        runA.put("datasetId", ds);
        runA.put("evaluators", List.of("number_accuracy"));
        long reportA = parse(postJson("/api/evaluations/run", asJson(runA))).path("data").path("id").asLong();

        Map<String, Object> runB = new LinkedHashMap<>();
        runB.put("datasetId", ds);
        runB.put("evaluators", List.of("number_accuracy", "string_exact"));
        long reportB = parse(postJson("/api/evaluations/run", asJson(runB))).path("data").path("id").asLong();

        // B 对比 A：number_accuracy 双端对齐 delta=0；string_exact 缺 baseline → current 有值 baseline null
        JsonNode data = parse(getJson("/api/evaluations/reports/" + reportB + "/compare?baseline=" + reportA))
                .path("data");
        assertThat(data.path("baseline").path("id").asLong()).isEqualTo(reportA);
        assertThat(data.path("current").path("id").asLong()).isEqualTo(reportB);
        JsonNode na = findCompareMetric(data, "number_accuracy");
        assertThat(na.path("current").asDouble()).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(na.path("baseline").asDouble()).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(na.path("delta").asDouble()).isCloseTo(0.0, within(0.001));
        assertThat(na.path("direction").asText()).isEqualTo("higher_is_better");
        JsonNode se = findCompareMetric(data, "string_exact");
        assertThat(se.path("current").asDouble()).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(se.path("baseline").isNull()).isTrue();
        assertThat(se.path("delta").isNull()).isTrue();

        // 反向：A 对比 B → string_exact 缺 current，number_accuracy delta 仍 0
        JsonNode data2 = parse(getJson("/api/evaluations/reports/" + reportA + "/compare?baseline=" + reportB))
                .path("data");
        JsonNode se2 = findCompareMetric(data2, "string_exact");
        assertThat(se2.path("current").isNull()).isTrue();
        assertThat(se2.path("baseline").asDouble()).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(se2.path("delta").isNull()).isTrue();

        // baseline 必填：缺参 → 400
        mvc.perform(get("/api/evaluations/reports/" + reportA + "/compare").header(AUTH, bearer()))
                .andExpect(status().isBadRequest());
    }

    // ---------- 5. 评测目录：15 内置 + 启用的自定义 ----------

    @Test
    void catalogListsBuiltinAndEnabledCustom() throws Exception {
        JsonNode catalog = parse(getJson("/api/evaluations/catalog")).path("data");
        assertThat(catalog.size()).isEqualTo(15);
        JsonNode na = null;
        JsonNode llm = null;
        for (JsonNode c : catalog) {
            assertThat(c.path("higherIsBetter").asBoolean()).isTrue();
            assertThat(c.path("defaultThreshold").asDouble()).isCloseTo(0.8, within(0.001));
            if ("number_accuracy".equals(c.path("metric").asText())) {
                na = c;
            }
            if ("llm_correctness".equals(c.path("metric").asText())) {
                llm = c;
            }
        }
        assertThat(na.path("category").asText()).isEqualTo("rule");
        assertThat(llm.path("category").asText()).isEqualTo("llm_judge");

        // ENABLED 自定义进入目录；DISABLED 不进入
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "目录自定义");
        body.put("category", "rule");
        body.put("description", "目录出现");
        body.put("ruleType", "keyword_contains");
        body.put("params", Map.of("keywords", List.of("正确")));
        body.put("judgePrompt", "");
        long ev = createCustomEvaluator(body);
        catalog = parse(getJson("/api/evaluations/catalog")).path("data");
        assertThat(catalog.size()).isEqualTo(16);
        boolean found = false;
        for (JsonNode c : catalog) {
            if (("custom_" + ev).equals(c.path("metric").asText())) {
                found = true;
            }
        }
        assertThat(found).isTrue();

        Map<String, Object> disabled = new LinkedHashMap<>(body);
        disabled.put("name", "目录停用");
        disabled.put("status", "DISABLED");
        long ev2 = createCustomEvaluator(disabled);
        catalog = parse(getJson("/api/evaluations/catalog")).path("data");
        assertThat(catalog.size()).isEqualTo(16);
        boolean absent = true;
        for (JsonNode c : catalog) {
            if (("custom_" + ev2).equals(c.path("metric").asText())) {
                absent = false;
            }
        }
        assertThat(absent).isTrue();
    }

    // ---------- helpers ----------

    private long createCustomEvaluator(Map<String, Object> body) throws Exception {
        String resp = postJson("/api/evaluations/custom-evaluators", asJson(body));
        return Long.parseLong(JsonPath.read(resp, "$.data.id").toString());
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

    private JsonNode findCompareMetric(JsonNode data, String metric) {
        for (JsonNode m : data.path("metrics")) {
            if (metric.equals(m.path("metric").asText())) {
                return m;
            }
        }
        return null;
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

    /** 等待任务进入 RUNNING 且 testedCases < total（取消竞争必须落在执行中）。 */
    private JsonNode waitRunningWithProgress(long id, int total) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode task = null;
        do {
            task = taskDetail(id).path("task");
            if ("RUNNING".equals(task.path("status").asText())
                    && task.path("testedCases").asInt() < total) {
                return task;
            }
            Thread.sleep(2);
        } while (System.currentTimeMillis() < deadline);
        throw new IllegalStateException("任务未进入 RUNNING 部分进度状态: " + task);
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

    private String putJson(String path, String body) throws Exception {
        return mvc.perform(put(path).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long auditCount() {
        return inTenant(() -> agentAuditMapper.selectCount(null));
    }

    private String lastAuditLine() {
        AgentAudit audit = inTenant(() -> agentAuditMapper.selectList(
                Wrappers.<AgentAudit>lambdaQuery().orderByDesc(AgentAudit::getId).last("limit 1"))).get(0);
        return audit.getAgentType() + "|" + audit.getAction() + "|" + audit.getStatus() + "|"
                + audit.getSchemaValid() + "|" + audit.getStrategyVersion();
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