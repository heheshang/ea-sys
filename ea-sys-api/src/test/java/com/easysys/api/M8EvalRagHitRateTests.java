package com.easysys.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 验收（V17）：execute 专属规则评测器 rag_hit_rate 端到端。
 *
 * <p>链路：上传知识库文档 → createDataset(execute/assistant) → addCase 带 expectedKbHits（不传
 * providedResponse 触发真实执行）→ POST /api/evaluations/run evaluators=[rag_hit_rate] →
 * 命中用例按 search_kb 实际工具结果出分；无检索命中的用例不适用（INFO 发现，不进 metrics）。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M8EvalRagHitRateTests {

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
    AgentAuditMapper agentAuditMapper;

    @Autowired
    EvaluationReportMapper reportMapper;

    @Autowired
    RedissonClient redisson;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH = "Authorization";
    /** 知识库文档（与 AssistantChatIT 同款：命中「会员权益包括」「积分翻倍」期望片段）。 */
    private static final String KB_CONTENT = "会员权益包括：生日礼遇、积分翻倍、专属客服、免运费。"
            + "新会员注册后 7 天内可领取新人礼包。";

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

    // ---------- 1. execute 命中 + 未命中混合：avg 取可判用例，未命中不适用 ----------

    @Test
    void executeRagHitRateAggregatesHitAndNotApplicable() throws Exception {
        // 知识库文档（BM25 检索命中「新会员有哪些权益？」的查询二元组）
        long docId = uploadDocument("会员权益说明.md", KB_CONTENT);
        assertThat(docId).isPositive();

        long ds = createDataset("RAG 判分", "execute", "assistant");
        // 命中用例：期望片段均落在 search_kb 命中文档 → 1.0
        long hitCase = addCase(ds, "新会员有哪些权益？", List.of("会员权益包括", "积分翻倍"));
        assertThat(hitCase).isPositive();
        // 未命中用例：KB 无考勤内容 → search_kb hits 为空 → 不适用（null，不进分母）
        addCase(ds, "介绍一下公司的考勤打卡规则", List.of("考勤打卡规则"));

        // 契约层装配：expectedKbHits 落库且读回为 JSON 数组
        JsonNode cases = parse(getJson("/api/evaluations/datasets/" + ds + "/cases")).path("data");
        assertThat(cases.get(0).path("expectedKbHits").isArray()).isTrue();
        assertThat(cases.get(0).path("expectedKbHits").get(0).asText()).isEqualTo("会员权益包括");
        assertThat(cases.get(1).path("expectedKbHits").get(0).asText()).isEqualTo("考勤打卡规则");

        // 运行：rag_hit_rate 真实执行评测（无 providedResponse）
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", List.of("rag_hit_rate"));
        JsonNode data = parse(postJson("/api/evaluations/run", asJson(run))).path("data");

        assertThat(data.path("testedCases").asInt()).isEqualTo(2);
        assertThat(data.path("mode").asText()).isEqualTo("execute");
        JsonNode metrics = data.path("metrics");
        // 命中 1 例出分（1.0）；未命中例不适用 → 适用数 1、均值 1.0、category=rule
        assertThat(metricApplicable(metrics, "rag_hit_rate")).isEqualTo(1);
        assertThat(metricScore(metrics, "rag_hit_rate")).isCloseTo(1.0, within(0.001));
        for (JsonNode m : metrics) {
            if ("rag_hit_rate".equals(m.path("metric").asText())) {
                assertThat(m.path("category").asText()).isEqualTo("rule");
            }
        }
        // 报告落库 + 审计形状不变
        assertThat(inTenant(() -> reportMapper.selectCount(new LambdaQueryWrapper<EvaluationReport>()
                .eq(EvaluationReport::getTenantId, 1L)))).isEqualTo(1);
        assertThat(lastAuditLine()).isEqualTo("EVALUATION|evaluation_run|SUCCESS|true|rule");
    }

    // ---------- 2. 全未命中：metrics 不含、INFO 发现列出（不报错） ----------

    @Test
    void executeRagHitRateMissReportsInfoFinding() throws Exception {
        uploadDocument("会员权益说明.md", KB_CONTENT);
        long ds = createDataset("RAG 未命中", "execute", "assistant");
        addCase(ds, "介绍一下公司的考勤打卡规则", List.of("考勤打卡规则"));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", List.of("rag_hit_rate"));
        JsonNode data = parse(postJson("/api/evaluations/run", asJson(run))).path("data");

        assertThat(metricApplicable(data.path("metrics"), "rag_hit_rate")).isEqualTo(-1);
        boolean info = false;
        for (JsonNode f : data.path("findings")) {
            if ("INFO".equals(f.path("level").asText())
                    && "rag_hit_rate".equals(f.path("dimension").asText())) {
                info = true;
            }
        }
        assertThat(info).isTrue();
    }

    // ---------- 3. 目录：rag_hit_rate 以 rule 类别 + execute 专属描述发布 ----------

    @Test
    void catalogListsRagHitRateWithDescription() throws Exception {
        JsonNode catalog = parse(getJson("/api/evaluations/catalog")).path("data");
        JsonNode rag = null;
        for (JsonNode item : catalog) {
            if ("rag_hit_rate".equals(item.path("metric").asText())) {
                rag = item;
            }
        }
        assertThat(rag).as("catalog 应含 rag_hit_rate").isNotNull();
        assertThat(rag.path("category").asText()).isEqualTo("rule");
        String description = rag.path("description").asText();
        assertThat(description).contains("search_kb");
        assertThat(description).contains("execute");
    }

    // ---------- helpers ----------

    private long uploadDocument(String name, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
        String s = mvc.perform(multipart("/api/assistant/documents").file(file).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private long createDataset(String name, String mode, String agentType) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("scope", "llm_call");
        m.put("mode", mode);
        m.put("agentType", agentType);
        String body = postJson("/api/evaluations/datasets", asJson(m));
        return Long.parseLong(JsonPath.read(body, "$.data.id").toString());
    }

    /** execute 执行维度用例：带 expectedKbHits 基准，不传 providedResponse（真实执行）。 */
    private long addCase(long datasetId, String question, List<String> expectedKbHits) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question", question);
        m.put("expectedKbHits", expectedKbHits);
        String body = postJson("/api/evaluations/datasets/" + datasetId + "/cases", asJson(m));
        return Long.parseLong(JsonPath.read(body, "$.data.id").toString());
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

    private double metricScore(JsonNode metrics, String metric) {
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText())) {
                return m.path("avg_score").asDouble(-1);
            }
        }
        return -1;
    }

    private int metricApplicable(JsonNode metrics, String metric) {
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText())) {
                return m.path("applicable_count").asInt(-1);
            }
        }
        return -1;
    }

    private String lastAuditLine() {
        AgentAudit audit = inTenant(() -> agentAuditMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentAudit>lambdaQuery()
                        .orderByDesc(AgentAudit::getId).last("limit 1"))).get(0);
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