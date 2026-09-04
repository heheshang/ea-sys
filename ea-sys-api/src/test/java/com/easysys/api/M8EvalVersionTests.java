package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.EvaluationCase;
import com.easysys.api.entity.EvaluationDataset;
import com.easysys.api.entity.EvaluationDatasetVersion;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.entity.EvaluationTask;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.EvaluationCaseMapper;
import com.easysys.api.mapper.EvaluationDatasetMapper;
import com.easysys.api.mapper.EvaluationDatasetVersionMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
import com.easysys.api.mapper.EvaluationTaskMapper;
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

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 扩展：评测数据集版本（P0/P1）——发布/列表/快照回看/删除、run/task 绑定版本快照、
 * 缺省取最新已发布版本（未发布回退实时）、V18 回填存量数据集、运行时分层（layering）
 * WARNING 校验。核心断言：无变更发布 400、被引用版本禁止删除、报告溯源
 * datasetVersionId/datasetVersionNo、快照 13 键全字段同构、空数据集 '[]'。
 *
 * <p>测试环境 LLM 未启用，判分走确定性规则（number_accuracy/string_exact）。</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M8EvalVersionTests {

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

    @Autowired
    EvaluationDatasetMapper datasetMapper;

    @Autowired
    EvaluationCaseMapper caseMapper;

    @Autowired
    EvaluationReportMapper reportMapper;

    @Autowired
    EvaluationDatasetVersionMapper versionMapper;

    @Autowired
    EvaluationTaskMapper taskMapper;

    @Autowired
    DataSource dataSource;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH = "Authorization";
    private static final Set<String> SNAPSHOT_KEYS = Set.of(
            "seq", "question", "system_prompt", "category", "judge_rule", "dialogue",
            "expected_output", "tool_schema", "expected_tool", "expected_steps",
            "expected_policy", "expected_kb_hits", "provided_response");

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

    // ---------- 1. 发布 / 列表 / 快照回看 / 删除 / 审计 ----------

    @Test
    void publishVersionListsAndReadsSnapshot() throws Exception {
        long ds = createDataset("版本发布", "openjudge");
        long c1 = addCase(ds, "基础问题", 1, "a");
        addCase(ds, "边界问题", 2, "b", "edge");
        addCase(ds, "真实问题", 3, "c", "real");

        // 数据集列表：无版本 + 三档计数
        JsonNode row = datasetRow(ds);
        assertThat(row.path("latestVersionId").isNull()).isTrue();
        assertThat(row.path("latestVersionNo").isNull()).isTrue();
        JsonNode cbc = row.path("caseCountByCategory");
        assertThat(cbc.path("basic").asInt()).isEqualTo(1);
        assertThat(cbc.path("edge").asInt()).isEqualTo(1);
        assertThat(cbc.path("real").asInt()).isEqualTo(1);
        assertThat(row.path("caseCount").asInt()).isEqualTo(3);

        // 发布 v1：versionNo=1 / PUBLISHED / 3 例
        JsonNode v1 = publishVersion(ds);
        long v1Id = v1.path("id").asLong();
        assertThat(v1.path("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(v1.path("caseCount").asInt()).isEqualTo(3);
        assertThat(v1.path("createdBy").asText()).isEqualTo("admin");

        // 无变更重复发布 → 400
        mvc.perform(post("/api/evaluations/datasets/" + ds + "/versions")
                        .header(AUTH, bearer()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // 版本列表 1 行
        JsonNode versions = parse(getJson("/api/evaluations/datasets/" + ds + "/versions")).path("data");
        assertThat(versions.size()).isEqualTo(1);
        assertThat(versions.get(0).path("versionNo").asInt()).isEqualTo(1);

        // 快照回看：seq 序 + category 透传（basic 缺省归一）
        JsonNode snapshotCases = parse(getJson(
                "/api/evaluations/datasets/" + ds + "/versions/" + v1Id + "/cases")).path("data");
        assertThat(snapshotCases.size()).isEqualTo(3);
        assertThat(snapshotCases.get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(snapshotCases.get(0).path("category").asText()).isEqualTo("basic");
        assertThat(snapshotCases.get(1).path("category").asText()).isEqualTo("edge");
        assertThat(snapshotCases.get(2).path("category").asText()).isEqualTo("real");

        // 改用例 → 发布 v2 → 数据集列表最新版本 = v2
        putJson("/api/evaluations/cases/" + c1, asJson(Map.of("providedResponse", "a2")));
        JsonNode v2 = publishVersion(ds);
        long v2Id = v2.path("id").asLong();
        assertThat(v2.path("versionNo").asInt()).isEqualTo(2);
        row = datasetRow(ds);
        assertThat(row.path("latestVersionId").asLong()).isEqualTo(v2Id);
        assertThat(row.path("latestVersionNo").asInt()).isEqualTo(2);

        // 删除 v1（无引用）→ 200；列表剩 v2
        mvc.perform(delete("/api/evaluations/datasets/" + ds + "/versions/" + v1Id)
                        .header(AUTH, bearer()))
                .andExpect(status().isOk());
        versions = parse(getJson("/api/evaluations/datasets/" + ds + "/versions")).path("data");
        assertThat(versions.size()).isEqualTo(1);
        assertThat(versions.get(0).path("versionNo").asInt()).isEqualTo(2);

        // 审计：发布 ×2 + 删除 ×1
        assertThat(auditCount("EVALUATION_DATASET_VERSION_PUBLISH")).isEqualTo(2);
        assertThat(auditCount("EVALUATION_DATASET_VERSION_DELETE")).isEqualTo(1);
    }

    // ---------- 2. run 绑定版本快照（锁历史 / 缺省最新 / 不存在 404） ----------

    @Test
    void runPinnedToVersionUsesSnapshot() throws Exception {
        long ds = createDataset("版本锁定运行", "openjudge");
        long c1 = addCase(ds, "42 的数值是？", 42, "41");
        JsonNode v1 = publishVersion(ds);
        long v1Id = v1.path("id").asLong();
        putJson("/api/evaluations/cases/" + c1, asJson(Map.of("providedResponse", "42")));
        JsonNode v2 = publishVersion(ds);
        long v2Id = v2.path("id").asLong();

        // v1 快照 provided=41 → 0.0；报告溯源 v1
        JsonNode r1 = run(ds, List.of("number_accuracy"), v1Id);
        assertThat(metricScore(r1.path("metrics"), "number_accuracy")).isCloseTo(0.0, within(0.001));
        assertThat(r1.path("datasetVersionId").asLong()).isEqualTo(v1Id);
        assertThat(r1.path("datasetVersionNo").asInt()).isEqualTo(1);

        // v2 快照 provided=42 → 1.0
        JsonNode r2 = run(ds, List.of("number_accuracy"), v2Id);
        assertThat(metricScore(r2.path("metrics"), "number_accuracy")).isCloseTo(1.0, within(0.001));
        assertThat(r2.path("datasetVersionNo").asInt()).isEqualTo(2);

        // 缺省 → 最新已发布 v2（不随工作区漂移）
        JsonNode r3 = run(ds, List.of("number_accuracy"), null);
        assertThat(metricScore(r3.path("metrics"), "number_accuracy")).isCloseTo(1.0, within(0.001));
        assertThat(r3.path("datasetVersionNo").asInt()).isEqualTo(2);

        // 绑定不存在的版本 → 404
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("datasetId", ds);
        bad.put("evaluators", List.of("number_accuracy"));
        bad.put("datasetVersionId", 999999L);
        mvc.perform(post("/api/evaluations/run").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(asJson(bad)))
                .andExpect(status().isNotFound());
    }

    // ---------- 3. task 绑定版本：params 固化 usedVersion + 报告溯源 ----------

    @Test
    void taskBindsVersionFromParamsAndReport() throws Exception {
        long ds = createDataset("任务版本绑定", "openjudge");
        long c1 = addCase(ds, "42 的数值是？", 42, "41");
        JsonNode v1 = publishVersion(ds);
        long v1Id = v1.path("id").asLong();
        // 工作区已改 provided=42，但任务绑定 v1 快照（provided=41）
        putJson("/api/evaluations/cases/" + c1, asJson(Map.of("providedResponse", "42")));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("datasetId", ds);
        req.put("evaluators", List.of("number_accuracy"));
        req.put("datasetVersionId", v1Id);
        long taskId = Long.parseLong(JsonPath.read(
                postTask("/api/evaluations/tasks", asJson(req)), "$.data.id").toString());
        JsonNode task = waitTask(taskId, "COMPLETED");
        assertThat(task.path("totalCases").asInt()).isEqualTo(1);

        // params 固化 usedVersion（versionId/versionNo 指向 v1）
        String params = inTenant(() -> taskMapper.selectById(taskId).getParams());
        JsonNode used = parse(params).path("usedVersion");
        assertThat(used.path("datasetId").asLong()).isEqualTo(ds);
        assertThat(used.path("versionId").asLong()).isEqualTo(v1Id);
        assertThat(used.path("versionNo").asInt()).isEqualTo(1);

        // 报告溯源 v1 且按快照判分（number_accuracy=0.0）
        long reportId = task.path("reportId").asLong(-1);
        assertThat(reportId).isGreaterThan(0);
        JsonNode report = parse(getJson("/api/evaluations/reports/" + reportId)).path("data");
        assertThat(report.path("datasetVersionId").asLong()).isEqualTo(v1Id);
        assertThat(report.path("datasetVersionNo").asInt()).isEqualTo(1);
        assertThat(metricScore(report.path("metrics"), "number_accuracy")).isCloseTo(0.0, within(0.001));
    }

    // ---------- 4. V18 回填：存量数据集 v1 快照（含空数据集 '[]'）+ 报告溯源回填 ----------

    @Test
    void backfillCreatesV1ForExistingDatasets() throws Exception {
        // 造存量数据（不走 API，直接落库模拟迁移前状态）
        EvaluationDataset ds1 = new EvaluationDataset();
        ds1.setTenantId(1L);
        ds1.setName("存量数据集");
        ds1.setScope("llm_call");
        ds1.setMode("openjudge");
        EvaluationDataset ds2 = new EvaluationDataset();
        ds2.setTenantId(1L);
        ds2.setName("存量空数据集");
        ds2.setScope("llm_call");
        ds2.setMode("openjudge");
        long[] ids = inTenant(() -> {
            datasetMapper.insert(ds1);
            datasetMapper.insert(ds2);
            EvaluationCase c1 = new EvaluationCase();
            c1.setTenantId(1L);
            c1.setDatasetId(ds1.getId());
            c1.setSeq(1);
            c1.setQuestion("问题一");
            c1.setProvidedResponse("回答一");
            EvaluationCase c2 = new EvaluationCase();
            c2.setTenantId(1L);
            c2.setDatasetId(ds1.getId());
            c2.setSeq(2);
            c2.setQuestion("问题二");
            c2.setProvidedResponse("回答二");
            caseMapper.insert(c1);
            caseMapper.insert(c2);
            EvaluationReport r = new EvaluationReport();
            r.setTenantId(1L);
            r.setDatasetId(ds1.getId());
            r.setName("迁移前报告");
            reportMapper.insert(r);
            return new long[]{ds1.getId(), ds2.getId(), r.getId(), c1.getId(), c2.getId()};
        });
        long ds1Id = ids[0];
        long ds2Id = ids[1];
        long reportId = ids[2];

        // 读取 V18 → 截取 BACKFILL 标记后的语句逐条执行（Flyway 已建表，只跑回填）
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V18__evaluation_rebuild.sql")) {
            assertThat(in).as("V18 迁移文件应在 classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int marker = sql.indexOf("-- ===BACKFILL===");
        assertThat(marker).isGreaterThan(-1);
        String tail = sql.substring(marker + "-- ===BACKFILL===".length());
        try (Connection conn = dataSource.getConnection()) {
            for (String stmt : tail.split(";")) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try (Statement s = conn.createStatement()) {
                    s.execute(trimmed);
                }
            }
        }
        // 有用例数据集 → v1 / PUBLISHED / 快照 2 例（13 键、seq 升序、category basic）
        Long v1Id = inTenant(() -> versionMapper.selectList(Wrappers.<EvaluationDatasetVersion>lambdaQuery()
                        .eq(EvaluationDatasetVersion::getDatasetId, ds1Id))
                .get(0).getId());
        JsonNode cases = inTenant(() -> parse(versionMapper.selectById(v1Id).getCases()));
        assertThat(cases.size()).isEqualTo(2);
        for (JsonNode n : cases) {
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("快照 13 键必须全含").containsExactlyInAnyOrderElementsOf(SNAPSHOT_KEYS);
            assertThat(n.path("category").asText()).isEqualTo("basic");
        }
        assertThat(cases.get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(cases.get(1).path("seq").asInt()).isEqualTo(2);
        assertThat(cases.get(0).path("question").asText()).isEqualTo("问题一");

        // 空数据集 → v1 / cases '[]'
        JsonNode empty = inTenant(() -> parse(versionMapper.selectList(
                        Wrappers.<EvaluationDatasetVersion>lambdaQuery()
                                .eq(EvaluationDatasetVersion::getDatasetId, ds2Id))
                .get(0).getCases()));
        assertThat(empty.isArray()).isTrue();
        assertThat(empty.size()).isZero();

        // 报告溯源回填 = v1
        EvaluationReport backfilled = inTenant(() -> reportMapper.selectById(reportId));
        assertThat(backfilled.getDatasetVersionId()).isEqualTo(v1Id);
        assertThat(backfilled.getDatasetVersionNo()).isEqualTo(1);
    }

    // ---------- 5. 分层校验：三档占比 <20% → WARNING（只追加不替换） ----------

    @Test
    void runWarnsOnSkewedLayering() throws Exception {
        // 对照组：2/2/2 达标 → 无 layering 发现
        long ok = createDataset("分层达标", "openjudge");
        addCase(ok, "b1", 1, "1", "basic");
        addCase(ok, "b2", 1, "1", "basic");
        addCase(ok, "e1", 1, "1", "edge");
        addCase(ok, "e2", 1, "1", "edge");
        addCase(ok, "r1", 1, "1", "real");
        addCase(ok, "r2", 1, "1", "real");
        JsonNode okData = run(ok, List.of("string_exact"), null);
        assertThat(hasFinding(okData, "layering")).isFalse();

        // 6 例全 basic → 占比 100/0/0 → WARNING layering
        long skewed = createDataset("分层偏差", "openjudge");
        for (int i = 0; i < 6; i++) {
            addCase(skewed, "s" + i, 1, "1");
        }
        JsonNode skewData = run(skewed, List.of("string_exact"), null);
        JsonNode finding = findLayering(skewData);
        assertThat(finding).as("全 basic 运行应产出 layering WARNING").isNotNull();
        assertThat(finding.path("level").asText()).isEqualTo("WARNING");
        assertThat(finding.path("detail").asText()).contains("分层偏差：参与用例 basic=6/edge=0/real=0");
    }

    // ---------- helpers ----------

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
        return addCase(datasetId, question, expectedOutput, providedResponse, null);
    }

    private long addCase(long datasetId, String question, Object expectedOutput, String providedResponse,
                         String category) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question", question);
        m.put("expectedOutput", expectedOutput);
        m.put("providedResponse", providedResponse);
        if (category != null) {
            m.put("category", category);
        }
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

    private JsonNode datasetRow(long ds) throws Exception {
        JsonNode list = parse(getJson("/api/evaluations/datasets")).path("data");
        for (JsonNode n : list) {
            if (n.path("id").asLong() == ds) {
                return n;
            }
        }
        return null;
    }

    private double metricScore(JsonNode metrics, String metric) {
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText())) {
                return m.path("avg_score").asDouble(-1);
            }
        }
        return -1;
    }

    private boolean hasFinding(JsonNode data, String dimension) {
        return findLayering(data, dimension) != null;
    }

    private JsonNode findLayering(JsonNode data) {
        return findLayering(data, "layering");
    }

    private JsonNode findLayering(JsonNode data, String dimension) {
        for (JsonNode f : data.path("findings")) {
            if (dimension.equals(f.path("dimension").asText())) {
                return f;
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

    /** 在默认租户上下文内执行 mapper 调用（租户插件要求）。 */
    private <T> T inTenant(Supplier<T> action) {
        TenantContext.set(new TenantInfo(1L));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 同上，用于 void 操作（如清表）。 */
    private void inTenantRun(Runnable action) {
        TenantContext.set(new TenantInfo(1L));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }
}