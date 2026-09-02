package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.mapper.WorkflowMapper;
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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 创建工作流验收（WORKFLOW agent，确定性主路径）：
 * 自然语言 → DAG 草稿 + 工具时间线（list_channels / search_templates / search_audiences
 * / build_dag / validate_dag），不自动落库；人群未匹配仅提示；保存仍走人工审核闸门
 * （缺模板/人群 → 400）；审计写入 audit_log（agentType=WORKFLOW）。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AiWorkflowTests {

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

    private static final String AUTH = "Authorization";

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowMapper workflowMapper;

    @Autowired
    AgentAuditMapper agentAuditMapper;

    @Autowired
    RedissonClient redisson;

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

    @Test
    void aiGenerateBuildsDraftWithToolTimelineWithoutPersisting() throws Exception {
        createTemplate("sms", "618大促通知", "亲爱的会员，618 大促限时开启，满 300 减 60！");
        createAudience("近30天未购买会员", rule("AND", List.of(cond("attribute.last_buy_days", "gt", 30))));

        long before = inTenant(() -> workflowMapper.selectCount(null));
        String body = mvc.perform(post("/api/workflows/ai-generate").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"每天上午9点向近30天未购买会员发送短信,使用 618大促通知 模板\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowDraft.name").isNotEmpty())
                .andExpect(jsonPath("$.data.workflowDraft.nodes.length()").value(3))
                .andExpect(jsonPath("$.data.workflowDraft.edges.length()").value(2))
                .andExpect(jsonPath("$.data.workflowDraft.nodes[0].type").value("TRIGGER"))
                .andExpect(jsonPath("$.data.workflowDraft.nodes[0].config.cron").value("0 0 9 * * ?"))
                .andExpect(jsonPath("$.data.workflowDraft.nodes[1].type").value("ACTION"))
                .andExpect(jsonPath("$.data.workflowDraft.nodes[1].config.channel").value("sms"))
                .andExpect(jsonPath("$.data.planSummary").isNotEmpty())
                .andExpect(jsonPath("$.data.audienceHint.matched").value(true))
                .andExpect(jsonPath("$.data.audienceHint.audienceName").value("近30天未购买会员"))
                .andReturn().getResponse().getContentAsString();

        // 工具时间线：5 次调用全成功
        List<String> tools = JsonPath.read(body, "$.data.toolCalls[*].name");
        assertThat(tools).containsExactly("list_channels", "search_templates",
                "search_audiences", "build_dag", "validate_dag");
        assertThat(JsonPath.<List<String>>read(body, "$.data.toolCalls[?(@.status!='SUCCESS')].name")).isEmpty();

        // 草稿不落库
        assertThat(inTenant(() -> workflowMapper.selectCount(null))).isEqualTo(before);
    }

    @Test
    void unmatchedAudienceHintsManualReviewAndTemplateStaysNull() throws Exception {
        createTemplate("email", "新用户欢迎邮件", "欢迎加入，领取新人礼包。");
        // 无「高价值客户」人群

        String body = mvc.perform(post("/api/workflows/ai-generate").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"每天向高价值客户发送邮件,使用 新用户欢迎邮件 模板\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audienceHint.matched").value(false))
                .andExpect(jsonPath("$.data.audienceHint.note").value(org.hamcrest.Matchers.containsString("人工圈选")))
                .andExpect(jsonPath("$.data.workflowDraft.nodes[0].config.audienceId").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        // 草稿保存被审核闸门拦截：ACTION 缺 templateId（邮件通道无匹配模板）→ 400
        mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(extractDraftJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("audienceId")));
        assertThat(inTenant(() -> workflowMapper.selectCount(null))).isZero();
    }

    @Test
    void generationIsAuditedWithWorkflowAgentType() throws Exception {
        mvc.perform(post("/api/workflows/ai-generate").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"每天8点发送短信提醒\"}"))
                .andExpect(status().isOk());

        AgentAudit audit = inTenant(() -> agentAuditMapper.selectList(
                        Wrappers.<AgentAudit>lambdaQuery().eq(AgentAudit::getAgentType, "WORKFLOW")))
                .get(0);
        assertThat(audit.getAction()).isEqualTo("workflow_generate");
        assertThat(audit.getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getSchemaValid()).isTrue();
        assertThat(audit.getConfidence()).isEqualByComparingTo("1");
        assertThat(audit.getOperator()).isEqualTo("admin");
        assertThat(audit.getInputSummary()).contains("每天8点发送短信提醒");
        assertThat(audit.getOutput()).contains("\"nodes\"");
    }

    // ---- 基建 ----

    private String extractDraftJson(String body) throws Exception {
        Object raw = JsonPath.read(body, "$.data.workflowDraft");
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
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

    private String audienceBody(String name, String ruleJson) {
        return asJson(Map.of("name", name, "rule", "PLACEHOLDER")).replace("\"PLACEHOLDER\"", ruleJson);
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

    private void inTenant(Runnable action) {
        TenantContext.set(new TenantInfo(1L));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }
}