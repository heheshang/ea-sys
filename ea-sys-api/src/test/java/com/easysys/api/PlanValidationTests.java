package com.easysys.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.SysUser;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.SysUserMapper;
import com.easysys.api.mapper.TenantSeedMapper;
import com.easysys.api.mapper.ValidationReportMapper;
import com.easysys.api.service.ChannelConfigService;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.mapper.WorkflowMapper;
import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ①计划导入校验验收：文件解析（xlsx/csv）→ 8 维度一致性校验 → 报告落库 + 审计 → 发布闸门。
 * 全流程跑在隔离租户 99999：种子独立 sys_user 登录（JWT tid=99999），
 * workflow/template/channel_config/validation_report 均落租户 99999，不触碰租户 1 无凭据契约。
 * 断言：
 *   PASSED（全一致）；通道未接入 BLOCKED；通道无凭据 WARNINGS；
 *   模板缺失 BLOCKED；cron 不一致 WARNINGS；触发方式不同 BLOCKED；频率>1 BLOCKED；
 *   发布闸门：BLOCKED 报告拦截 publish，重导 PASSED 后放行；回看 latest；csv 路径；模板下载。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PlanValidationTests {

    private static final long T2 = 99999L;

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
    RedissonClient redisson;

    @Autowired
    WorkflowMapper workflowMapper;

    @Autowired
    SysUserMapper sysUserMapper;

    @Autowired
    TenantSeedMapper tenantSeedMapper;

    @Autowired
    ChannelConfigService channelConfigService;

    @Autowired
    ValidationReportMapper validationReportMapper;

    @Autowired
    AgentAuditMapper agentAuditMapper;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        inTenant(workflowMapper::testTruncateAll);
        tenantSeedMapper.seedTenant(T2, "校验隔离租户");
        if (inTenant(() -> sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, "pv-user"))) == 0) {
            SysUser u = new SysUser();
            u.setTenantId(T2);
            u.setUsername("pv-user");
            u.setRole("admin");
            u.setStatus("active");
            u.setPasswordHash(new BCryptPasswordEncoder().encode("admin123"));
            inTenant(() -> sysUserMapper.insert(u));
        }
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pv-user\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- 1. 全一致 → PASSED（xlsx 路径 + 回看） ----------

    @Test
    void passedWhenPlanMatchesWorkflow() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key", "signName", "营销"), true));

        byte[] xlsx = xlsxDoc("618 大促召回", "TIMED", "0 0 9 * * 1", "sms", "大促召回短信", "1", "D+0");
        importPlan(wf, "plan-ok.xlsx", xlsx, "PASSED");

        // 回看 latest：报告字段与文件类型归一化（DB XLSX → 视图 xlsx）
        mvc.perform(get("/api/plan-validation/{workflowId}", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision").value("PASSED"))
                .andExpect(jsonPath("$.data.fileType").value("xlsx"))
                .andExpect(jsonPath("$.data.fileName").value("plan-ok.xlsx"))
                .andExpect(jsonPath("$.data.planSummary").value(org.hamcrest.Matchers.containsString("618 大促召回")))
                .andExpect(jsonPath("$.data.summary.conflicts").value(0))
                .andExpect(jsonPath("$.data.summary.warnings").value(0))
                .andExpect(jsonPath("$.data.summary.passed").value(8))
                .andExpect(jsonPath("$.data.dimensions[?(@.name=='channel')].level").value("PASSED"));

        // 审计落库：PLAN_VALIDATION / import_validate / SUCCESS
        Long audits = inTenant(() -> agentAuditMapper.selectCount(
                Wrappers.<AgentAudit>lambdaQuery()
                        .eq(AgentAudit::getAgentType, "PLAN_VALIDATION")
                        .eq(AgentAudit::getAction, "import_validate")
                        .eq(AgentAudit::getStatus, "SUCCESS")));
        assertThat(audits).isEqualTo(1L);
    }

    // ---------- 2. 通道未接入（push 无适配器）→ BLOCKED ----------

    @Test
    void blocksUnregisteredChannel() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key"), true));

        importPlan(wf, "plan-push.xlsx",
                xlsxDoc("推送计划", "TIMED", "0 0 9 * * 1", "push", "大促召回短信", "1", "D+0"),
                "BLOCKED");
    }

    // ---------- 3. 通道无凭据（无 channel_config 行）→ WARNINGS ----------

    @Test
    void warnsMissingChannelCredentials() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);

        importPlan(wf, "plan-nocfg.xlsx",
                xlsxDoc("无凭据计划", "TIMED", "0 0 9 * * 1", "sms", "大促召回短信", "1", "D+0"),
                "WARNINGS");
    }

    // ---------- 4. 模板缺失 → BLOCKED ----------

    @Test
    void blocksMissingTemplate() throws Exception {
        long wf = createWorkflow("0 0 9 * * 1", null, createTemplate("sms", "大促召回短信", "Hi ${name!}"));
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key"), true));

        importPlan(wf, "plan-badtpl.xlsx",
                xlsxDoc("缺模板计划", "TIMED", "0 0 9 * * 1", "sms", "不存在的模板", "1", "D+0"),
                "BLOCKED");
    }

    // ---------- 5. cron 不一致 → WARNINGS ----------

    @Test
    void warnsCronMismatch() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key"), true));

        importPlan(wf, "plan-cron.xlsx",
                xlsxDoc("cron 不一致", "TIMED", "0 0 18 * * 1", "sms", "大促召回短信", "1", "D+0"),
                "WARNINGS");
    }

    // ---------- 6. 触发方式不同（计划 EVENT vs 工作流 SCHEDULED）→ BLOCKED ----------

    @Test
    void blocksTriggerTypeMismatch() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key"), true));

        importPlan(wf, "plan-evt.xlsx",
                xlsxDoc("事件计划", "EVENT", "", "sms", "大促召回短信", "1", "D+0"),
                "BLOCKED");
    }

    // ---------- 7. 单用户频率上限 >1 → BLOCKED ----------

    @Test
    void blocksFrequencyOverOne() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key"), true));

        importPlan(wf, "plan-freq.xlsx",
                xlsxDoc("高频计划", "TIMED", "0 0 9 * * 1", "sms", "大促召回短信", "2", "D+0"),
                "BLOCKED");
    }

    // ---------- 8. 发布闸门：BLOCKED 报告拦截 publish，PASSED 报告放行 ----------

    @Test
    void publishGateBlocksOnBlockedReportThenAllowsAfterPassed() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);

        // 先导入一份 BLOCKED 报告（push 未接入）
        importPlan(wf, "plan-bad.xlsx",
                xlsxDoc("推送计划", "TIMED", "0 0 9 * * 1", "push", "大促召回短信", "1", "D+0"),
                "BLOCKED");
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("计划校验未通过")));

        // 修复：给通道配凭据 + 导入 PASSED 报告 → 放行
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key"), true));
        importPlan(wf, "plan-ok.xlsx",
                xlsxDoc("618 大促召回", "TIMED", "0 0 9 * * 1", "sms", "大促召回短信", "1", "D+0"),
                "PASSED");
        mvc.perform(post("/api/workflows/{id}/publish", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("published"));
    }

    // ---------- 9. 无报告回看 → data=null ----------

    @Test
    void latestIsNullBeforeAnyImport() throws Exception {
        long wf = createWorkflow("0 0 9 * * 1", null, createTemplate("sms", "大促召回短信", "Hi ${name!}"));

        mvc.perform(get("/api/plan-validation/{workflowId}", wf).header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------- 10. csv 路径：同样产出 PASSED ----------

    @Test
    void csvPathProducesSameDecision() throws Exception {
        long template = createTemplate("sms", "大促召回短信", "Hi ${name!}");
        long wf = createWorkflow("0 0 9 * * 1", null, template);
        inTenant(() -> channelConfigService.save(T2, "sms",
                Map.of("endpoint", "http://sms.example.com/send", "apiKey", "test-key"), true));

        importPlan(wf, "plan-ok.csv", csvDoc("csv 计划", "TIMED", "0 0 9 * * 1", "sms", "大促召回短信", "1", "D+0"),
                "PASSED");
    }

    // ---------- 11. 模板下载接口 ----------

    @Test
    void downloadsTemplates() throws Exception {
        mvc.perform(get("/api/plan-validation/template").param("type", "xlsx").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("plan-import-template.xlsx")));

        mvc.perform(get("/api/plan-validation/template").param("type", "csv").header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")));
    }

    // ---------- helpers ----------

    private void importPlan(long wf, String filename, byte[] bytes, String expectedDecision) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);
        MvcResult result = mvc.perform(multipart("/api/plan-validation/{workflowId}/import", wf)
                        .file(file)
                        .header(AUTH, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision").value(expectedDecision))
                .andReturn();
        System.out.println("IMPORT RESPONSE [" + filename + "]: " + result.getResponse().getContentAsString());
    }

    /** 生成 4 Sheet（计划概览/触达计划/人群规则/文案要求）xlsx，表头 + 1 数据行。 */
    private byte[] xlsxDoc(String planName, String trigger, String cron, String channel,
                           String templateName, String freq, String timing) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet overview = wb.createSheet("计划概览");
            String[] ovHead = {"计划名称", "目标人群", "触发方式", "触发时间", "事件名", "时区", "预算上限"};
            Row h = overview.createRow(0);
            for (int c = 0; c < ovHead.length; c++) {
                h.createCell(c).setCellValue(ovHead[c]);
            }
            String eventName = "EVENT".equals(trigger) ? "order_created" : "";
            String[] ovData = {planName, "近 30 天未购会员", trigger, cron, eventName, "Asia/Shanghai", "5000"};
            Row d = overview.createRow(1);
            for (int c = 0; c < ovData.length; c++) {
                d.createCell(c).setCellValue(ovData[c]);
            }

            Sheet routes = wb.createSheet("触达计划");
            String[] rtHead = {"人群分层", "通道", "顺序", "时刻/延迟", "消息模板", "单用户频率上限", "备注"};
            Row rh = routes.createRow(0);
            for (int c = 0; c < rtHead.length; c++) {
                rh.createCell(c).setCellValue(rtHead[c]);
            }
            String[] rtData = {"未购会员", channel, "1", timing, templateName, freq, "首触"};
            Row rd = routes.createRow(1);
            for (int c = 0; c < rtData.length; c++) {
                rd.createCell(c).setCellValue(rtData[c]);
            }

            Sheet audience = wb.createSheet("人群规则");
            String[] auHead = {"操作", "字段", "操作符", "值"};
            Row ah = audience.createRow(0);
            for (int c = 0; c < auHead.length; c++) {
                ah.createCell(c).setCellValue(auHead[c]);
            }
            String[] auData = {"包含", "last_purchase_days", ">=", "30"};
            Row ad = audience.createRow(1);
            for (int c = 0; c < auData.length; c++) {
                ad.createCell(c).setCellValue(auData[c]);
            }

            Sheet copy = wb.createSheet("文案要求");
            String[] cpHead = {"通道", "模板", "要求"};
            Row ch = copy.createRow(0);
            for (int c = 0; c < cpHead.length; c++) {
                ch.createCell(c).setCellValue(cpHead[c]);
            }
            String[] cpData = {channel, templateName, "突出折扣力度"};
            Row cd = copy.createRow(1);
            for (int c = 0; c < cpData.length; c++) {
                cd.createCell(c).setCellValue(cpData[c]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** csv：#节标记 + 表头 + 数据行，与 xlsx 同构。 */
    private byte[] csvDoc(String planName, String trigger, String cron, String channel,
                          String templateName, String freq, String timing) {
        String eventName = "EVENT".equals(trigger) ? "order_created" : "";
        String csv = "#计划概览\n"
                + "计划名称,目标人群,触发方式,触发时间,事件名,时区,预算上限\n"
                + planName + ",近 30 天未购会员," + trigger + "," + cron + "," + eventName + ",Asia/Shanghai,5000\n"
                + "\n#触达计划\n"
                + "人群分层,通道,顺序,时刻/延迟,消息模板,单用户频率上限,备注\n"
                + "未购会员," + channel + ",1," + timing + "," + templateName + "," + freq + ",首触\n"
                + "\n#人群规则\n"
                + "操作,字段,操作符,值\n"
                + "包含,last_purchase_days,>=,30\n"
                + "\n#文案要求\n"
                + "通道,模板,要求\n"
                + channel + "," + templateName + ",突出折扣力度\n";
        return csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private long createWorkflow(String cron, String eventName, long templateId) throws Exception {
        long audience = createAudience("pv-aud", rule("AND", List.of(cond("attribute.churn_risk", "equals", "HIGH"))));
        Map<String, Object> triggerCfg = new LinkedHashMap<>();
        triggerCfg.put("triggerType", "SCHEDULED");
        triggerCfg.put("cron", cron);
        triggerCfg.put("timezone", "Asia/Shanghai");
        triggerCfg.put("audienceId", audience);
        if (eventName != null) {
            triggerCfg.put("triggerType", "EVENT");
            triggerCfg.put("eventName", eventName);
            triggerCfg.remove("audienceId");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "pv-wf");
        body.put("description", "计划校验工作流");
        body.put("nodes", List.of(
                node("trigger", "TRIGGER", "开始", triggerCfg),
                node("act1", "ACTION", "发送短信", Map.of("channel", "sms", "templateId", templateId, "unitCost", 0.05)),
                node("end", "END", "结束", null)));
        body.put("edges", List.of(edge("trigger", "act1"), edge("act1", "end")));
        String s = mvc.perform(post("/api/workflows").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
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

    private Map<String, Object> edge(String source, String target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        return m;
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private long createAudience(String name, String ruleJson) throws Exception {
        String s = mvc.perform(post("/api/audiences").header(AUTH, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(audienceBody(name, ruleJson)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(s, "$.data.id").toString());
    }

    private String audienceBody(String name, String ruleJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("rule", "PLACEHOLDER");
        return asJson(m).replace("\"PLACEHOLDER\"", ruleJson);
    }

    private String rule(String op, List<String> items) {
        return "{\"op\":\"" + op + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    private String cond(String field, String op, Object value) {
        return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + asJson(value) + "}";
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

    private String asJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T inTenant(Supplier<T> supplier) {
        TenantContext.set(new TenantInfo(T2));
        try {
            return supplier.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Runnable runnable) {
        inTenant(() -> {
            runnable.run();
            return null;
        });
    }
}