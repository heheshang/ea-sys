package com.easysys.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.EvaluationReport;
import com.easysys.api.entity.LlmUsage;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.EvaluationReportMapper;
import com.easysys.api.mapper.LlmUsageMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
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
import redis.clients.jedis.JedisPooled;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 验收：驾驶舱（图谱登记+状态管理、监控总览聚合、洞察 COCKPIT Agent 规则降级 + 缓存）
 * + 评测中心（数据集/用例 CRUD、批量运行 EVALUATION Agent 规则判分、报告落库 + 审计）。
 *
 * <p>规则确定性断言：number_accuracy/string_exact 按类内算法直接推算期望值；洞察/评测均走
 * AgentPolicy 确定性规划器（LLM 未启用），audit_log 记录 agent_type|action|status|schema|版本。</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class M8CockpitEvaluationTests {

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
    LlmUsageMapper llmUsageMapper;

    @Autowired
    JedisPooled agentscopeJedisPooled;

    @Autowired
    RedissonClient redisson;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH = "Authorization";
    private static final String INSIGHT_CACHE_KEY = "easysys:cockpit:insights:1";

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

    // ---------- 1. 监控总览：audit_log 聚合 + 图谱计数 + 记忆键 ----------

    @Test
    void cockpitOverviewAggregatesLlmAndGraph() throws Exception {
        // audit_log 种子：LAYER 成功调用（近 7 天）
        inTenant(() -> {
            AgentAudit a = new AgentAudit();
            a.setTenantId(1L);
            a.setAgentType("LAYER");
            a.setAction("strategy_generate");
            a.setStatus("SUCCESS");
            a.setSchemaValid(true);
            a.setStrategyVersion("rule");
            a.setConfidence(BigDecimal.ONE);
            a.setModel("deterministic");
            a.setTokens(100);
            a.setDurationMs(50L);
            a.setOperator("admin");
            a.setCreatedAt(Instant.now());
            agentAuditMapper.insert(a);
        });
        // 记忆键：agentscope 会话键
        redisson.getBucket("easysys:agentscope:m8:1").set("{\"session\":true}");
        // 图谱：内置 25 项 + 用户新增 1 项
        createGraphEntry("ONTOLOGY", "m8_entry", "M8 用户条目");

        JsonNode data = parse(getJson("/api/cockpit/overview")).path("data");

        // LLM 聚合：初始化 1 次（种子），byAgent 含 LAYER 行
        assertThat(data.path("llm").path("calls").asLong()).isEqualTo(1);
        JsonNode byAgent = data.path("llm").path("byAgent");
        assertThat(byAgent.isArray()).isTrue();
        JsonNode layer = findSeries(byAgent, "LAYER");
        assertThat(layer).isNotNull();
        assertThat(layer.path("calls").asLong()).isEqualTo(1);
        assertThat(layer.path("sumTokens").asLong()).isEqualTo(100);
        assertThat(layer.path("avgDurationMs").asDouble()).isCloseTo(50.0, within(0.001));

        // llm_usage 明细字段（测试环境 LLM 未启用 → 空表 COALESCE=0 / context null）
        JsonNode llm = data.path("llm");
        assertThat(llm.path("rounds").asLong()).isZero();
        assertThat(llm.path("sumInputTokens").asLong()).isZero();
        assertThat(llm.path("sumOutputTokens").asLong()).isZero();
        assertThat(llm.path("sumCachedTokens").asLong()).isZero();
        assertThat(llm.path("context").isNull()).isTrue();

        // 图谱：25 内置 + 1 用户 = 26；ONTOLOGY 模块 7 内置 + 1 用户 = 8
        assertThat(data.path("graph").path("total").asLong()).isEqualTo(26);
        // 25 内置（MCP/SUBAGENT DISABLED）+ 用户 1：enabled = 23 + 1
        assertThat(data.path("graph").path("enabled").asLong()).isEqualTo(24);
        JsonNode modules = data.path("graph").path("modules");
        JsonNode ontology = null;
        for (JsonNode m : modules) {
            if ("ONTOLOGY".equals(m.path("module").asText())) {
                ontology = m;
            }
        }
        assertThat(ontology).isNotNull();
        assertThat(ontology.path("total").asLong()).isEqualTo(8);

        // 知识库/记忆字段存在
        assertThat(data.path("knowledge").path("docs").isNumber()).isTrue();
        assertThat(data.path("memory").path("keys").asLong()).isEqualTo(1);

        // Agent 目录含 6 类（含 COCKPIT/EVALUATION）
        JsonNode byType = data.path("agents").path("byType");
        assertThat(byType.isArray()).isTrue();
        assertThat(byType.size()).isEqualTo(6);
        assertThat(byType.toString()).contains("COCKPIT", "EVALUATION");
    }

    // ---------- 1b. 上下文构成：查询期 AgentState 实时派生（转录剔尾 + 兜底） ----------

    /**
     * 派生主路径：llm_usage 只落会话台账行（markRound，无 context 快照）+ Redis 写入
     * workflow-dialogue AgentState 转录 → overview 上下文非空且六类构成来自真实转录
     * （末尾 ASSISTANT 最终回复被剔除，不参与构成）。
     */
    @Test
    void cockpitOverviewDerivesLlmContextFromAgentState() throws Exception {
        String sessionId = "m8-derived-session";
        String stateKey = "easysys:agentscope:session:1/" + sessionId + ":agent_state";
        // 会话台账行（无 context 快照）→ 快照兜底不可用，构成必须来自派生
        llmUsageMapper.markRound(1L, "workflow-dialogue", sessionId);
        try {
            // AgentState：与线上实测转录同构（workflow-dialogue，10 条消息，末尾 ASSISTANT 最终回复）。
            // 必须用 JedisPooled 写原始 JSON 文本——RedisDistributedStore 以字符串读写；redisson RBucket
            // 的默认 codec 会序列化成二进制，读回时 fromJsonString 解析失败。
            agentscopeJedisPooled.set(stateKey, """
                    {
                      "session_id": "%s",
                      "user_id": "1",
                      "summary": "",
                      "context": [
                        {"id":"m8-0","name":null,"role":"USER","content":[{"type":"text","text":"帮我查看今日发送情况"}],"metadata":{},"timestamp":"2026-09-03 13:53:44.561","usage":null},
                        {"id":"m8-1","name":"workflow-dialogue","role":"ASSISTANT","content":[{"type":"text","text":"我来查询发送通道。"},{"type":"tool_use","id":"m8-t1","name":"list_channels","input":{},"content":"{}","metadata":{},"state":"allowed"}],"metadata":{},"timestamp":"2026-09-03 13:53:44.570","usage":null},
                        {"id":"m8-2","name":"workflow-dialogue","role":"TOOL","content":[{"type":"tool_result","id":"m8-t1","name":"list_channels","output":[{"type":"text","text":"[{\\"channel\\":\\"email\\",\\"enabled\\":true}]"}],"metadata":{},"state":"success"}],"metadata":{},"timestamp":"2026-09-03 13:53:44.583","usage":null},
                        {"id":"m8-3","name":"workflow-dialogue","role":"ASSISTANT","content":[{"type":"text","text":"已查到通道。"},{"type":"tool_use","id":"m8-t2","name":"search_templates","input":{},"content":"{}","metadata":{},"state":"allowed"},{"type":"tool_use","id":"m8-t3","name":"search_audiences","input":{},"content":"{}","metadata":{},"state":"allowed"}],"metadata":{},"timestamp":"2026-09-03 13:53:44.600","usage":null},
                        {"id":"m8-4","name":"workflow-dialogue","role":"TOOL","content":[{"type":"tool_result","id":"m8-t2","name":"search_templates","output":[{"type":"text","text":"[{\\"id\\":1,\\"name\\":\\"促销通知\\"}]"}],"metadata":{},"state":"success"}],"metadata":{},"timestamp":"2026-09-03 13:53:44.610","usage":null},
                        {"id":"m8-5","name":"workflow-dialogue","role":"TOOL","content":[{"type":"tool_result","id":"m8-t3","name":"search_audiences","output":[{"type":"text","text":"[{\\"id\\":8,\\"name\\":\\"全量人群\\"}]"}],"metadata":{},"state":"success"}],"metadata":{},"timestamp":"2026-09-03 13:53:44.615","usage":null},
                        {"id":"m8-6","name":null,"role":"USER","content":[{"type":"text","text":"用模板一发给全量"}],"metadata":{},"timestamp":"2026-09-03 13:53:45.100","usage":null},
                        {"id":"m8-7","name":"workflow-dialogue","role":"ASSISTANT","content":[{"type":"text","text":"好的，开始创建。"},{"type":"tool_use","id":"m8-t4","name":"plan_workflow","input":{"prompt":"用模板一发给全量"},"content":"{\\"prompt\\":\\"用模板一发给全量\\"}","metadata":{},"state":"allowed"}],"metadata":{},"timestamp":"2026-09-03 13:53:45.120","usage":null},
                        {"id":"m8-8","name":"workflow-dialogue","role":"TOOL","content":[{"type":"tool_result","id":"m8-t4","name":"plan_workflow","output":[{"type":"text","text":"{\\"workflowDraft\\":{\\"name\\":\\"测试\\"}}"}],"metadata":{},"state":"success"}],"metadata":{},"timestamp":"2026-09-03 13:53:45.130","usage":null},
                        {"id":"m8-9","name":"workflow-dialogue","role":"ASSISTANT","content":[{"type":"text","text":"草稿已生成，请确认。"}],"metadata":{},"timestamp":"2026-09-03 13:53:45.140","usage":null}
                      ],
                      "reply_id": "m8-reply",
                      "cur_iter": 0,
                      "shutdown_interrupted": false,
                      "permission_context": {"mode":"default","working_directories":{},"allow_rules":{},"deny_rules":{},"ask_rules":{}},
                      "tool_context": {"max_cache_files":100,"max_cache_bytes":25000.0,"activated_groups":[],"spawn_registry":{}},
                      "tasks_context": {"tasks":[]},
                      "plan_mode_context": {"plan_active":false,"current_plan_file":null}
                    }
                    """.formatted(sessionId));

            JsonNode llm = parse(getJson("/api/cockpit/overview")).path("data").path("llm");
            JsonNode ctx = llm.path("context");

            // 派生主路径：快照为 null（markRound 无 context）→ 非空即证明来自 AgentState 转录
            assertThat(ctx.isNull())
                    .as("派生上下文（llm_usage 快照为 null 时应由 AgentState 转录实算）").isFalse();
            assertThat(ctx.path("tokens").asLong()).isGreaterThan(0);

            // 10 条转录剔末尾 ASSISTANT 最终回复 → 9 条：user 2 / assistant 3 / tool_result 4
            Map<String, JsonNode> cats = new LinkedHashMap<>();
            for (JsonNode c : ctx.path("categories")) {
                cats.put(c.path("key").asText(), c);
            }
            assertThat(cats.keySet()).containsExactly(
                    "system", "tool_schema", "user", "assistant", "injected", "tool_result");
            assertThat(cats.get("user").path("entries").asInt()).isEqualTo(2);
            assertThat(cats.get("assistant").path("entries").asInt()).isEqualTo(3);
            assertThat(cats.get("tool_result").path("entries").asInt()).isEqualTo(4);
            // 工具 Schema = delegate 注册工具全量（>0），system/injected 转录无 → 0
            assertThat(cats.get("tool_schema").path("entries").asInt()).isGreaterThan(0);
            assertThat(cats.get("tool_schema").path("tokens").asLong()).isGreaterThan(0);
            assertThat(cats.get("system").path("entries").asInt()).isZero();
            assertThat(cats.get("injected").path("entries").asInt()).isZero();
            // 9 条消息 + 工具 Schema 数
            assertThat(ctx.path("entries").asLong())
                    .isEqualTo(9 + cats.get("tool_schema").path("entries").asLong());
        } finally {
            // 清理台账行（保留 test 1 空表聚合断言）；state 键由 @BeforeEach flushall 兜底
            inTenant(() -> llmUsageMapper.delete(new LambdaQueryWrapper<LlmUsage>()
                    .eq(LlmUsage::getTenantId, 1L).eq(LlmUsage::getSessionId, sessionId)));
        }
    }

    // ---------- 2. 图谱 CRUD + 内置覆盖 ----------

    @Test
    void cockpitGraphCrud() throws Exception {
        // 内置目录：TOOL 9 项（含 query_stats，source=builtin）
        JsonNode items = parse(getJson("/api/cockpit/graph?module=TOOL")).path("data");
        assertThat(items.size()).isEqualTo(9);
        assertThat(hasItem(items, "query_stats", "builtin")).isTrue();

        // 用户登记同 key 覆盖内置（不增加行数）
        long id = createGraphEntry("TOOL", "query_stats", "查询统计（用户登记）");
        items = parse(getJson("/api/cockpit/graph?module=TOOL")).path("data");
        assertThat(items.size()).isEqualTo(9);
        JsonNode covered = findItem(items, "query_stats");
        assertThat(covered).isNotNull();
        assertThat(covered.path("source").asText()).isEqualTo("user");
        assertThat(covered.path("id").asLong()).isEqualTo(id);

        // 编辑 + 自定义 payload
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("module", "TOOL");
        upd.put("entryKey", "query_stats");
        upd.put("name", "查询统计（改名）");
        upd.put("payload", Map.of("owner", "m8"));
        JsonNode updated = parse(putJson("/api/cockpit/graph/" + id, asJson(upd))).path("data");
        assertThat(updated.path("name").asText()).isEqualTo("查询统计（改名）");
        assertThat(updated.path("payload").path("owner").asText()).isEqualTo("m8");

        // 状态开关
        JsonNode disabled = parse(patchJson("/api/cockpit/graph/" + id + "/status?status=DISABLED")).path("data");
        assertThat(disabled.path("status").asText()).isEqualTo("DISABLED");

        // 删除后恢复内置项
        mvc.perform(delete("/api/cockpit/graph/" + id).header(AUTH, bearer()))
                .andExpect(status().isOk());
        items = parse(getJson("/api/cockpit/graph?module=TOOL")).path("data");
        assertThat(items.size()).isEqualTo(9);
        assertThat(hasItem(items, "query_stats", "builtin")).isTrue();

        // 非法 module 拒绝
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("module", "BADMODULE");
        bad.put("entryKey", "x");
        bad.put("name", "x");
        mvc.perform(post("/api/cockpit/graph").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(asJson(bad)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 3. 洞察：AgentPolicy + 300s 缓存 + force ----------

    @Test
    void cockpitInsightsRunCachedAndForce() throws Exception {
        // 首次生成：overall_health + ≥1 条洞察，审计 COCKPIT|cockpit_insights|SUCCESS|true|rule
        JsonNode data = parse(getJson("/api/cockpit/insights")).path("data");
        assertThat(data.path("overallHealth").asInt(0)).isGreaterThanOrEqualTo(0);
        assertThat(data.path("insights").size()).isGreaterThanOrEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(lastAuditLine()).isEqualTo("COCKPIT|cockpit_insights|SUCCESS|true|rule");

        // 缓存回填：RBucket 有值
        RBucket<String> bucket = redisson.getBucket(INSIGHT_CACHE_KEY);
        assertThat(bucket.get()).isNotNull();

        // 未 force：缓存命中，不新增审计
        parse(getJson("/api/cockpit/insights"));
        assertThat(auditCount()).isEqualTo(1);

        // force=true：绕过缓存重新生成
        parse(getJson("/api/cockpit/insights?force=true"));
        assertThat(auditCount()).isEqualTo(2);
    }

    // ---------- 4. 评测运行：规则打分 + 指标均值 + 报告落库 + 审计 ----------

    @Test
    void evaluationRunsDatasetWithRuleScorers() throws Exception {
        long ds = createDataset("数字正确性", "openjudge");
        addCase(ds, "42 的数值是？", 42, "42");
        addCase(ds, "100 加 0 的数值是？", 100, "100");
        addCase(ds, "50 的数值是？", 50, "51");

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", java.util.List.of("number_accuracy", "string_exact"));
        JsonNode data = parse(postJson("/api/evaluations/run", asJson(run))).path("data");

        assertThat(data.path("testedCases").asInt()).isEqualTo(3);
        assertThat(data.path("totalCases").asInt()).isEqualTo(3);
        // number_accuracy：42✓ 100✓ 50✗ → 2/3；string_exact 同 2/3 → 平均 66.7 WARN
        assertThat(data.path("summary").path("score").asDouble()).isCloseTo(66.7, within(0.1));
        assertThat(data.path("summary").path("verdict").asText()).isEqualTo("WARN");

        JsonNode metrics = data.path("metrics");
        assertThat(metricScore(metrics, "number_accuracy")).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(metricScore(metrics, "string_exact")).isCloseTo(2.0 / 3.0, within(0.001));

        // findings：均值 <0.8 → WARNING 发现
        JsonNode findings = data.path("findings");
        boolean warned = false;
        for (JsonNode f : findings) {
            if ("number_accuracy".equals(f.path("dimension").asText())
                    && "WARNING".equals(f.path("level").asText())) {
                warned = true;
            }
        }
        assertThat(warned).isTrue();

        // 报告落库（租户上下文）+ 审计
        assertThat(inTenant(() -> reportMapper.selectCount(new LambdaQueryWrapper<EvaluationReport>()
                .eq(EvaluationReport::getTenantId, 1L)))).isEqualTo(1);
        assertThat(lastAuditLine()).isEqualTo("EVALUATION|evaluation_run|SUCCESS|true|rule");
    }

    // ---------- 5. 报告列表/详情/删除 ----------

    @Test
    void evaluationReportsListGetDelete() throws Exception {
        long ds = createDataset("报告回看", "openjudge");
        inTenant(() -> {
            EvaluationReport r = new EvaluationReport();
            r.setTenantId(1L);
            r.setDatasetId(ds);
            r.setName("报告回看");
            r.setTotalCases(1);
            r.setTestedCases(1);
            r.setMetrics("[]");
            r.setFindings("[]");
            r.setSummary("{\"score\":100,\"verdict\":\"PASS\"}");
            r.setConfidence(BigDecimal.ONE);
            r.setModel("deterministic");
            r.setMode("openjudge");
            r.setCreatedBy("admin");
            r.setCreatedAt(Instant.now());
            reportMapper.insert(r);
            return null;
        });

        JsonNode list = parse(getJson("/api/evaluations/reports")).path("data");
        assertThat(list.size()).isEqualTo(1);
        long rid = list.get(0).path("id").asLong();

        JsonNode detail = parse(getJson("/api/evaluations/reports/" + rid)).path("data");
        assertThat(detail.path("summary").path("verdict").asText()).isEqualTo("PASS");

        mvc.perform(delete("/api/evaluations/reports/" + rid).header(AUTH, bearer()))
                .andExpect(status().isOk());
        list = parse(getJson("/api/evaluations/reports")).path("data");
        assertThat(list.size()).isZero();
    }

    // ---------- 6. openjudge：预置响应判分，跳过被测执行 ----------

    @Test
    void evaluationOpenJudgeSkipsExecute() throws Exception {
        long ds = createDataset("开放判分", "openjudge");
        addCase(ds, "你是谁？", "EASYSYS助手", "EASYSYS助手");

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("datasetId", ds);
        run.put("evaluators", java.util.List.of("string_exact"));
        JsonNode data = parse(postJson("/api/evaluations/run", asJson(run))).path("data");

        assertThat(data.path("testedCases").asInt()).isEqualTo(1);
        assertThat(data.path("summary").path("score").asDouble()).isCloseTo(100.0, within(0.001));
        assertThat(data.path("summary").path("verdict").asText()).isEqualTo("PASS");
        assertThat(metricScore(data.path("metrics"), "string_exact")).isCloseTo(1.0, within(0.001));
    }

    // ---------- helpers ----------

    private long createGraphEntry(String module, String entryKey, String name) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", module);
        m.put("entryKey", entryKey);
        m.put("name", name);
        m.put("status", "ENABLED");
        String body = postJson("/api/cockpit/graph", asJson(m));
        return Long.parseLong(JsonPath.read(body, "$.data.id").toString());
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

    private String putJson(String path, String body) throws Exception {
        return mvc.perform(put(path).header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String patchJson(String path) throws Exception {
        return mvc.perform(patch(path).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode findSeries(JsonNode byAgent, String name) {
        for (JsonNode n : byAgent) {
            if (name.equals(n.path("name").asText())) {
                return n;
            }
        }
        return null;
    }

    private JsonNode findItem(JsonNode items, String entryKey) {
        for (JsonNode n : items) {
            if (entryKey.equals(n.path("entryKey").asText())) {
                return n;
            }
        }
        return null;
    }

    private boolean hasItem(JsonNode items, String entryKey, String source) {
        JsonNode it = findItem(items, entryKey);
        return it != null && source.equals(it.path("source").asText());
    }

    private double metricScore(JsonNode metrics, String metric) {
        for (JsonNode m : metrics) {
            if (metric.equals(m.path("metric").asText())) {
                return m.path("avg_score").asDouble(-1);
            }
        }
        return -1;
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