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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 扩展：P2 逐轮转录（evaluation_transcript）—— 多轮 execute 执行（同一会话连续调用，
 * AgentState 跨轮保留）产出 turn_no 递增的 USER/ASSISTANT/TOOL 转录行；单轮与 M8 逐字节
 * 一致（metrics 不变、转录仅 turn 1）；异步任务逐样本 latency_ms 出现且任务/报告转录一致；
 * openjudge 与失败任务（无报告）转录为空。结构断言为主（roles/轮次/顺序），不断言 LLM 内容。
 *
 * <p>测试环境 LLM 未启用（无 apiKey）：assistant 确定性策略路由，query_stats 无种子 → 空态
 * 「上周期 0 人…留存率约 0.0%」类回复，快速可复现。多轮追问选用「我再确认一下留存率」
 * （含「留存」→ retention 意图，确定性出数），避开触发工作流/达标线等 HITL/知识库分支。</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M8EvalTranscriptTests {

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
        inTenant(workflowMapper::testTruncateAll);
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 1. 多轮 execute：同会话连续调用 → turn_no 1/2、逐轮 USER/ASSISTANT/TOOL 转录 ----------

    @Test
    void executeMultiTurnCaseRecordsTwoTurnTranscript() throws Exception {
        long ds = createDataset("多轮转录", "execute", "assistant");
        addCaseExec(ds, "查一下近 30 天留存率", Map.of("name", "query_stats"), 2,
                List.of(Map.of("keyword", "留存率", "prohibit", false)),
                List.of(Map.of("role", "user", "content", "我再确认一下留存率")));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", List.of("tool_call_accuracy", "step_efficiency", "policy_compliance"));
        JsonNode data = parse(postJson("/api/evaluations/run", asJson(run))).path("data");

        assertThat(data.path("testedCases").asInt()).isEqualTo(1);
        assertThat(data.path("mode").asText()).isEqualTo("execute");
        long reportId = data.path("id").asLong();

        JsonNode rows = transcriptRows("/api/evaluations/reports/" + reportId + "/transcript?caseSeq=1");
        assertThat(rows.size()).isGreaterThanOrEqualTo(6); // 2 USER + 2 ASSISTANT + tool_use + tool_result
        // 逐轮递增：首行 turn 1，且存在 turn 2（多轮会话真实发生）
        assertThat(rows.get(0).path("turnNo").asInt()).isEqualTo(1);
        boolean hasTurn2 = false;
        for (JsonNode r : rows) {
            int tn = r.path("turnNo").asInt();
            if (tn == 2) {
                hasTurn2 = true;
            }
            assertThat(tn).isBetween(1, 2);
            assertThat(r.path("role").asText()).isIn("USER", "ASSISTANT", "TOOL");
        }
        assertThat(hasTurn2).as("多轮用例应产生 turn 2 转录").isTrue();

        // 每轮恰好一条 USER：turn1 = question，turn2 = 追问
        List<JsonNode> users = new ArrayList<>();
        for (JsonNode r : rows) {
            if ("USER".equals(r.path("role").asText())) {
                users.add(r);
            }
        }
        assertThat(users.size()).isEqualTo(2);
        assertThat(users.get(0).path("turnNo").asInt()).isEqualTo(1);
        assertThat(users.get(0).path("text").asText()).isEqualTo("查一下近 30 天留存率");
        assertThat(users.get(1).path("turnNo").asInt()).isEqualTo(2);
        assertThat(users.get(1).path("text").asText()).isEqualTo("我再确认一下留存率");

        // ASSISTANT 两轮均有非空可见回复
        int assistantRows = 0;
        for (JsonNode r : rows) {
            if ("ASSISTANT".equals(r.path("role").asText())) {
                assistantRows++;
                assertThat(r.path("text").asText()).isNotBlank();
            }
        }
        assertThat(assistantRows).isGreaterThanOrEqualTo(2);

        // TOOL 行：tool_use 记录 query_stats（agentscope 2.0.2 工具调用挂 ASSISTANT 消息 → 已归一化）
        boolean sawQueryStats = false;
        for (JsonNode r : rows) {
            if ("TOOL".equals(r.path("role").asText())
                    && "query_stats".equals(r.path("toolUse").path("name").asText())) {
                sawQueryStats = true;
            }
        }
        assertThat(sawQueryStats).as("转录应含 query_stats 工具调用行").isTrue();

        // 判分输入已切换多轮：metrics 仍出分（1 例适用）
        assertThat(metricApplicable(data.path("metrics"), "tool_call_accuracy")).isEqualTo(1);
    }

    // ---------- 2. 单轮 execute：turn 仅 1、metrics 与 M8 一致（逐字节不变锚点） ----------

    @Test
    void executeSingleTurnCaseRecordsOneTurnAndScoresAsBefore() throws Exception {
        long ds = createDataset("单轮转录", "execute", "assistant");
        addCaseExec(ds, "查一下近 30 天留存率", Map.of("name", "query_stats"), 2,
                List.of(Map.of("keyword", "留存率", "prohibit", false)), null);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", List.of("tool_call_accuracy", "step_efficiency", "policy_compliance"));
        JsonNode data = parse(postJson("/api/evaluations/run", asJson(run))).path("data");
        long reportId = data.path("id").asLong();

        // M8 单轮锚点：metrics 与既有 execute 判分口径一致
        assertThat(metricApplicable(data.path("metrics"), "tool_call_accuracy")).isEqualTo(1);
        assertThat(metricScore(data.path("metrics"), "tool_call_accuracy")).isCloseTo(1.0,
                org.assertj.core.api.Assertions.within(0.001));
        assertThat(metricScore(data.path("metrics"), "step_efficiency")).isCloseTo(1.0,
                org.assertj.core.api.Assertions.within(0.001));
        assertThat(metricScore(data.path("metrics"), "policy_compliance")).isCloseTo(1.0,
                org.assertj.core.api.Assertions.within(0.001));

        JsonNode rows = transcriptRows("/api/evaluations/reports/" + reportId + "/transcript?caseSeq=1");
        assertThat(rows.size()).isGreaterThanOrEqualTo(3); // USER + ASSISTANT + tool_result（+tool_use）
        int users = 0;
        for (JsonNode r : rows) {
            assertThat(r.path("turnNo").asInt()).as("单轮用例全为 turn 1").isEqualTo(1);
            if ("USER".equals(r.path("role").asText())) {
                users++;
                assertThat(r.path("text").asText()).isEqualTo("查一下近 30 天留存率");
            }
        }
        assertThat(users).isEqualTo(1);
    }

    // ---------- 3. 异步任务：sample_results 暴露 latency_ms、任务/报告转录一致 ----------

    @Test
    void asyncTaskExecuteModeExposesSampleLatencyAndTranscript() throws Exception {
        long ds = createDataset("异步转录", "execute", "assistant");
        addCaseExec(ds, "查一下近 30 天留存率", Map.of("name", "query_stats"), 2,
                List.of(Map.of("keyword", "留存率", "prohibit", false)), null);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", List.of("tool_call_accuracy"));
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());

        JsonNode task = waitTask(taskId, "COMPLETED");
        long reportId = task.path("reportId").asLong(-1);
        assertThat(reportId).isGreaterThan(0);

        // 逐样本 latency_ms：仅 execute 路径写入（整例全部轮次毫秒）
        JsonNode samples = task.path("sampleResults");
        assertThat(samples.isArray()).isTrue();
        assertThat(samples.size()).isEqualTo(1);
        assertThat(samples.get(0).path("latency_ms").isNumber()).isTrue();
        assertThat(samples.get(0).path("latency_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(samples.get(0).path("metrics").size()).isGreaterThan(0);

        // 任务转录与报告转录一致（同一报告）且非空
        JsonNode taskRows = transcriptRows("/api/evaluations/tasks/" + taskId + "/transcript?caseSeq=1");
        JsonNode reportRows = transcriptRows("/api/evaluations/reports/" + reportId + "/transcript?caseSeq=1");
        assertThat(taskRows.size()).isGreaterThanOrEqualTo(3);
        assertThat(reportRows.size()).isEqualTo(taskRows.size());
        for (int i = 0; i < reportRows.size(); i++) {
            assertThat(reportRows.get(i).path("turnNo").asInt())
                    .isEqualTo(taskRows.get(i).path("turnNo").asInt());
            assertThat(reportRows.get(i).path("role").asText())
                    .isEqualTo(taskRows.get(i).path("role").asText());
        }
    }

    // ---------- 4. 空转录路径 + 守卫：openjudge 空、失败任务空、caseSeq 必填 400、不存在 404 ----------

    @Test
    void openjudgeAndFailedTaskYieldEmptyTranscriptWithGuards() throws Exception {
        // openjudge：无 executeSubject → 无转录行
        long ds = createDataset("开放判空", "openjudge", null);
        addCase(ds, "查一下留存", null, "暂无运营数据");
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", List.of("string_exact"));
        long reportId = parse(postJson("/api/evaluations/run", asJson(run))).path("data").path("id").asLong();
        assertThat(transcriptRows("/api/evaluations/reports/" + reportId + "/transcript?caseSeq=1"))
                .isEmpty();

        // 失败任务（数据集停用）无报告 → 任务转录空
        long ds2 = createDataset("停用转录", "openjudge", null);
        addCase(ds2, "q", "期望", "响应");
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("name", "停用转录");
        upd.put("scope", "llm_call");
        upd.put("mode", "openjudge");
        upd.put("status", "DISABLED");
        putJson("/api/evaluations/datasets/" + ds2, asJson(upd));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds2);
        req.put("evaluators", List.of("string_exact"));
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());
        JsonNode task = waitTask(taskId, "FAILED");
        assertThat(task.path("reportId").isNull()).isTrue();
        assertThat(transcriptRows("/api/evaluations/tasks/" + taskId + "/transcript?caseSeq=1"))
                .isEmpty();

        // caseSeq 必填 → 400；不存在的报告/任务 → 404
        mvc.perform(get("/api/evaluations/reports/" + reportId + "/transcript").header(AUTH, bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/evaluations/tasks/" + taskId + "/transcript").header(AUTH, bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/evaluations/reports/999999999/transcript?caseSeq=1").header(AUTH, bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/evaluations/tasks/999999999/transcript?caseSeq=1").header(AUTH, bearer()))
                .andExpect(status().isNotFound());
    }

    // ---------- 5. 对话去重/跳过：尾随 user==question 去重、非 user 角色不入轮次（单轮不变） ----------

    @Test
    void dialogueDedupeAndNonUserEntriesKeepSingleTurn() throws Exception {
        long ds = createDataset("去重转录", "execute", "assistant");
        // 追问 1 = 与 question 相同（去重）；追问 2 = system 角色（跳过）→ 仍单轮
        List<Map<String, String>> dialogue = List.of(
                Map.of("role", "user", "content", "查一下近 30 天留存率"),
                Map.of("role", "system", "content", "仅做提示，不入轮次"));
        addCaseExec(ds, "查一下近 30 天留存率", Map.of("name", "query_stats"), 2,
                List.of(Map.of("keyword", "留存率", "prohibit", false)), dialogue);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", List.of("tool_call_accuracy"));
        long reportId = parse(postJson("/api/evaluations/run", asJson(run))).path("data").path("id").asLong();

        JsonNode rows = transcriptRows("/api/evaluations/reports/" + reportId + "/transcript?caseSeq=1");
        int users = 0;
        for (JsonNode r : rows) {
            assertThat(r.path("turnNo").asInt()).as("去重/跳过应保持单轮").isEqualTo(1);
            if ("USER".equals(r.path("role").asText())) {
                users++;
            }
        }
        assertThat(users).isEqualTo(1);
    }

    // ---------- helpers ----------

    private JsonNode transcriptRows(String path) throws Exception {
        return parse(getJson(path)).path("data");
    }

    private long createDataset(String name, String mode, String agentType) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("scope", "llm_call");
        m.put("mode", mode);
        if (agentType != null) {
            m.put("agentType", agentType);
        }
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

    /** execute 执行维度用例：真实执行（不传 providedResponse），可带 dialogue 多轮追问。 */
    private long addCaseExec(long datasetId, String question, Object expectedTool, Integer expectedSteps,
                             Object expectedPolicy, Object dialogue) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question", question);
        if (expectedTool != null) {
            m.put("expectedTool", expectedTool);
        }
        if (expectedSteps != null) {
            m.put("expectedSteps", expectedSteps);
        }
        if (expectedPolicy != null) {
            m.put("expectedPolicy", expectedPolicy);
        }
        if (dialogue != null) {
            m.put("dialogue", dialogue);
        }
        String body = postJson("/api/evaluations/datasets/" + datasetId + "/cases", asJson(m));
        return Long.parseLong(JsonPath.read(body, "$.data.id").toString());
    }

    /** metrics[] 中某指标的适用用例数；未出现返回 -1。 */
    private int metricApplicable(JsonNode metrics, String metric) {
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText())) {
                return m.path("applicable_count").asInt(-1);
            }
        }
        return -1;
    }

    private double metricScore(JsonNode metrics, String metric) {
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText())) {
                return m.path("avg_score").asDouble(-1);
            }
        }
        return -1;
    }

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

    private String putJson(String path, String body) throws Exception {
        return mvc.perform(put(path).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
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