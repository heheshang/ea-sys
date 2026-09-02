package com.easysys.api;

import com.easysys.api.dto.channel.ChannelConfigView;
import com.easysys.api.mapper.ChannelConfigMapper;
import com.easysys.api.mapper.TenantSeedMapper;
import com.easysys.api.service.ChannelConfigService;
import com.easysys.channel.ChannelAdapter;
import com.easysys.channel.ChannelConfigProvider;
import com.easysys.channel.HttpSmsChannelAdapter;
import com.easysys.channel.SmtpEmailChannelAdapter;
import com.easysys.channel.WechatChannelAdapter;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.engine.mapper.WorkflowMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
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

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 扩展验收：真实供应商通道适配器 + 按租户凭据注入。
 * 1) SMTP 邮件适配器真实投递（in-JVM GreenMail 断言收件/内容/发件人）；
 * 2) 通用 HTTP 短信适配器（JDK HttpServer mock 断言请求体/鉴权头/幂等键）；
 * 3) 无凭据降级 console 日志（channelMessageId 前缀 "console-"，M3 回归契约）；
 * 4) channel_config 加密落库 + 对外脱敏 + 租户隔离（凭据按租户注入）。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ChannelConfigTests {

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
    ChannelConfigMapper channelConfigMapper;

    @Autowired
    ChannelConfigService channelConfigService;

    @Autowired
    ChannelConfigProvider channelConfigProvider;

    @Autowired
    TenantSeedMapper tenantSeedMapper;

    @Autowired
    RedissonClient redisson;

    private static final String AUTH = "Authorization";

    private String token;

    @BeforeEach
    void login() throws Exception {
        inTenant(1L, workflowMapper::testTruncateAll);
        redisson.getKeys().flushall();
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.data.token");
    }

    // ---------- SMTP 邮件适配器：in-JVM GreenMail 真实投递 ----------

    private GreenMail greenMail;

    @BeforeEach
    void startGreenMail() {
        greenMail = new GreenMail(new ServerSetup(0, null, "smtp"));
        greenMail.start();
    }

    @AfterEach
    void stopGreenMail() {
        greenMail.stop();
    }

    private ChannelConfigProvider fakeConfig(Map<String, String> cfg) {
        return (tenantId, channel) -> Optional.of(cfg);
    }

    private static final ChannelConfigProvider NO_CONFIG = (tenantId, channel) -> Optional.empty();

    private ChannelAdapter.SendRequest sendRequest(String address) {
        return new ChannelAdapter.SendRequest(1L, 42L, 7L, "node-a", "1",
                "你好，这是触达内容 #001", "1:42:7:node-a", address);
    }

    @Test
    void smtpDeliversRealEmail() throws Exception {
        Map<String, String> cfg = Map.of(
                "smtpHost", "127.0.0.1",
                "smtpPort", String.valueOf(greenMail.getSmtp().getPort()),
                "fromEmail", "noreply@easysys.local");
        SmtpEmailChannelAdapter adapter = new SmtpEmailChannelAdapter(fakeConfig(cfg));

        ChannelAdapter.SendResult r = adapter.send(sendRequest("user@example.com"));

        assertThat(r.success()).isTrue();
        assertThat(r.channelMessageId()).startsWith("smtp-");
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(messages[0].getFrom()[0].toString()).isEqualTo("noreply@easysys.local");
        assertThat(messages[0].getContent().toString()).contains("你好，这是触达内容 #001");
    }

    @Test
    void smtpRejectsMissingAddress() {
        Map<String, String> cfg = Map.of("smtpHost", "127.0.0.1", "fromEmail", "noreply@easysys.local");
        SmtpEmailChannelAdapter adapter = new SmtpEmailChannelAdapter(fakeConfig(cfg));

        ChannelAdapter.SendResult r = adapter.send(sendRequest(null));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("邮箱");
    }

    @Test
    void smtpRejectsMissingFrom() {
        Map<String, String> cfg = Map.of("smtpHost", "127.0.0.1");
        SmtpEmailChannelAdapter adapter = new SmtpEmailChannelAdapter(fakeConfig(cfg));

        ChannelAdapter.SendResult r = adapter.send(sendRequest("user@example.com"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("fromEmail");
    }

    // ---------- 通用 HTTP 短信适配器：JDK HttpServer mock ----------

    private HttpServer smsMock;
    private final List<String> smsBodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, List<String>>> smsHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startSmsMock() throws Exception {
        smsMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        smsMock.createContext("/sms/send", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            smsBodies.add(new String(body, StandardCharsets.UTF_8));
            // HttpClient 会把头名规范化为 X-api-key 之类大小写，捕获用 case-insensitive Map 以便断言
            Map<String, List<String>> captured = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            captured.putAll(exchange.getRequestHeaders());
            smsHeaders.add(captured);
            byte[] resp = "{\"messageId\":\"mock-msg-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        smsMock.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        smsMock.start();
    }

    @AfterEach
    void stopSmsMock() {
        smsMock.stop(0);
    }

    private String smsEndpoint() {
        return "http://127.0.0.1:" + smsMock.getAddress().getPort() + "/sms/send";
    }

    @Test
    void smsPostsFormWithCredentials() {
        Map<String, String> cfg = Map.of(
                "endpoint", smsEndpoint(),
                "apiKey", "ak-123",
                "apiSecret", "as-secret-456",
                "signName", "测试签名");
        HttpSmsChannelAdapter adapter = new HttpSmsChannelAdapter(fakeConfig(cfg));

        ChannelAdapter.SendResult r = adapter.send(sendRequest("13900000001"));

        assertThat(r.success()).isTrue();
        assertThat(r.channelMessageId()).startsWith("sms-");
        assertThat(smsBodies).hasSize(1);
        String decoded = URLDecoder.decode(smsBodies.get(0), StandardCharsets.UTF_8);
        assertThat(decoded).contains("phone=13900000001");
        assertThat(decoded).contains("content=你好，这是触达内容 #001");
        assertThat(decoded).contains("idempotencyKey=1:42:7:node-a");
        assertThat(decoded).contains("signName=测试签名");
        assertThat(smsHeaders.get(0).get("X-Api-Key").getFirst()).isEqualTo("ak-123");
        assertThat(smsHeaders.get(0).get("X-Api-Secret").getFirst()).isEqualTo("as-secret-456");
        assertThat(smsHeaders.get(0).get("Content-Type").getFirst()).isEqualTo("application/x-www-form-urlencoded");
    }

    @Test
    void smsRejectsMissingAddress() {
        HttpSmsChannelAdapter adapter = new HttpSmsChannelAdapter(
                fakeConfig(Map.of("endpoint", smsEndpoint())));

        ChannelAdapter.SendResult r = adapter.send(sendRequest(null));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("手机号");
    }

    @Test
    void smsRejectsMissingEndpoint() {
        HttpSmsChannelAdapter adapter = new HttpSmsChannelAdapter(fakeConfig(Map.of()));

        ChannelAdapter.SendResult r = adapter.send(sendRequest("13900000001"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("endpoint");
    }

    @Test
    void smsFailsOnNon2xx() {
        HttpSmsChannelAdapter adapter = new HttpSmsChannelAdapter(
                fakeConfig(Map.of("endpoint",
                        "http://127.0.0.1:" + smsMock.getAddress().getPort() + "/error")));

        ChannelAdapter.SendResult r = adapter.send(sendRequest("13900000001"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("短信供应商返回 500");
    }

    // ---------- 微信模板消息适配器：JDK HttpServer mock 微信 API ----------

    private HttpServer wechatMock;
    private final List<String> wechatTokenPaths = new CopyOnWriteArrayList<>();
    private final List<String> wechatSendPaths = new CopyOnWriteArrayList<>();
    private final List<String> wechatBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startWechatMock() throws Exception {
        wechatMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wechatMock.createContext("/cgi-bin/token", exchange -> {
            wechatTokenPaths.add(exchange.getRequestURI().toString());
            byte[] resp = "{\"access_token\":\"mock-token-abc\",\"expires_in\":7200}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        wechatMock.createContext("/cgi-bin/message/template/send", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            wechatBodies.add(new String(body, StandardCharsets.UTF_8));
            wechatSendPaths.add(exchange.getRequestURI().toString());
            String resp = new String(body, StandardCharsets.UTF_8).contains("触发失败内容")
                    ? "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"
                    : "{\"errcode\":0,\"errmsg\":\"ok\"}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        wechatMock.start();
    }

    @AfterEach
    void stopWechatMock() {
        wechatMock.stop(0);
    }

    private String wechatBase() {
        return "http://127.0.0.1:" + wechatMock.getAddress().getPort();
    }

    @Test
    void wechatPostsTemplateMessageWithToken() {
        Map<String, String> cfg = Map.of(
                "appId", "wx-app-123",
                "appSecret", "wx-secret-456",
                "templateId", "tpl-001",
                "endpoint", wechatBase());
        WechatChannelAdapter adapter = new WechatChannelAdapter(fakeConfig(cfg));

        ChannelAdapter.SendResult r = adapter.send(sendRequest("openid-abc"));

        assertThat(r.success()).isTrue();
        assertThat(r.channelMessageId()).startsWith("wechat-");
        assertThat(wechatTokenPaths).hasSize(1);
        assertThat(wechatTokenPaths.get(0)).contains("appid=wx-app-123").contains("secret=wx-secret-456");
        assertThat(wechatSendPaths.get(0)).contains("access_token=mock-token-abc");
        assertThat(wechatBodies).hasSize(1);
        assertThat(wechatBodies.get(0))
                .contains("touser\":\"openid-abc")
                .contains("template_id\":\"tpl-001")
                .contains("你好，这是触达内容 #001");
    }

    @Test
    void wechatCachesAccessTokenBetweenSends() {
        Map<String, String> cfg = Map.of(
                "appId", "wx-app-123",
                "appSecret", "wx-secret-456",
                "templateId", "tpl-001",
                "endpoint", wechatBase());
        WechatChannelAdapter adapter = new WechatChannelAdapter(fakeConfig(cfg));

        ChannelAdapter.SendResult r1 = adapter.send(sendRequest("openid-abc"));
        ChannelAdapter.SendResult r2 = adapter.send(sendRequest("openid-abc"));

        assertThat(r1.success()).isTrue();
        assertThat(r2.success()).isTrue();
        assertThat(wechatTokenPaths).hasSize(1);
        assertThat(wechatBodies).hasSize(2);
    }

    @Test
    void wechatRejectsMissingOpenid() {
        WechatChannelAdapter adapter = new WechatChannelAdapter(
                fakeConfig(Map.of("appId", "a", "appSecret", "s", "templateId", "t", "endpoint", wechatBase())));

        ChannelAdapter.SendResult r = adapter.send(sendRequest(null));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("openid");
        assertThat(wechatTokenPaths).isEmpty();
    }

    @Test
    void wechatRejectsMissingCredentials() {
        WechatChannelAdapter adapter = new WechatChannelAdapter(
                fakeConfig(Map.of("appId", "wx-app-123", "appSecret", "wx-secret-456", "endpoint", wechatBase())));

        ChannelAdapter.SendResult r = adapter.send(sendRequest("openid-abc"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("appId/appSecret/templateId");
        assertThat(wechatTokenPaths).isEmpty();
    }

    @Test
    void wechatFailsOnNonZeroErrcode() {
        WechatChannelAdapter adapter = new WechatChannelAdapter(
                fakeConfig(Map.of("appId", "wx-app-123", "appSecret", "wx-secret-456",
                        "templateId", "tpl-001", "endpoint", wechatBase())));

        ChannelAdapter.SendResult r = adapter.send(new ChannelAdapter.SendRequest(1L, 42L, 7L, "node-a",
                "1", "触发失败内容", "1:42:7:node-a", "openid-abc"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("errcode").contains("40001");
        assertThat(wechatSendPaths).hasSize(1);
    }

    // ---------- 无凭据降级 console（M3 回归契约：前缀 "console-"） ----------

    @Test
    void fallsBackToConsoleWithoutCredentials() {
        SmtpEmailChannelAdapter mail = new SmtpEmailChannelAdapter(NO_CONFIG);
        HttpSmsChannelAdapter sms = new HttpSmsChannelAdapter(NO_CONFIG);
        WechatChannelAdapter wechat = new WechatChannelAdapter(NO_CONFIG);

        ChannelAdapter.SendResult mailR = mail.send(sendRequest("user@example.com"));
        ChannelAdapter.SendResult smsR = sms.send(sendRequest("13900000001"));
        ChannelAdapter.SendResult wechatR = wechat.send(sendRequest("openid-abc"));

        assertThat(mailR.success()).isTrue();
        assertThat(mailR.channelMessageId()).startsWith("console-");
        assertThat(smsR.success()).isTrue();
        assertThat(smsR.channelMessageId()).startsWith("console-");
        assertThat(wechatR.success()).isTrue();
        assertThat(wechatR.channelMessageId()).startsWith("console-");
    }

    // ---------- channel_config：加密落库 + 脱敏 + CRUD ----------

    @Test
    void apiCrudEncryptsAndMasks() throws Exception {
        mvc.perform(put("/api/channel-configs/sms")
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config\":{\"endpoint\":\"http://sms.example.com/send\"," +
                                "\"apiKey\":\"sk-super-secret\",\"apiSecret\":\"s3cr3t!\"," +
                                "\"signName\":\"营销\"},\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("sms"));

        mvc.perform(get("/api/channel-configs")
                        .header(AUTH, "Bearer " + token)
                        .param("channel", "sms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].config.apiKey").value("******"))
                .andExpect(jsonPath("$.data[0].config.apiSecret").value("******"))
                .andExpect(jsonPath("$.data[0].config.endpoint").value("http://sms.example.com/send"));

        // 落库为密文：明文凭据不出现
        List<String> encrypted = inTenant(1L, () -> channelConfigMapper.selectList(null))
                .stream().map(c -> c.getConfigEncrypted()).toList();
        assertThat(encrypted).hasSize(1);
        assertThat(encrypted.get(0)).doesNotContain("sk-super-secret", "s3cr3t!");

        // 服务层解密后与原值一致（凭据可被适配器消费）
        Optional<Map<String, String>> loaded = inTenant(1L,
                () -> channelConfigProvider.load(1L, "sms"));
        assertThat(loaded).isPresent();
        assertThat(loaded.get().get("apiKey")).isEqualTo("sk-super-secret");
        assertThat(loaded.get().get("apiSecret")).isEqualTo("s3cr3t!");
        assertThat(loaded.get().get("signName")).isEqualTo("营销");

        mvc.perform(delete("/api/channel-configs/sms")
                        .header(AUTH, "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/channel-configs")
                        .header(AUTH, "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(inTenant(1L, () -> channelConfigProvider.load(1L, "sms"))).isEmpty();
    }

    @Test
    void credentialsAreTenantIsolated() {
        tenantSeedMapper.seedTenant(99999L, "隔离租户");
        inTenant(99999L, () -> channelConfigService.save(99999L, "sms",
                Map.of("endpoint", "http://iso.example.com/send", "apiKey", "iso-only-key"), true));

        // 租户 1 无凭据（隔离）：适配器 dash 降级 console
        assertThat(inTenant(1L, () -> channelConfigProvider.load(1L, "sms"))).isEmpty();
        // 租户 99999 凭据解密可用
        assertThat(inTenant(99999L, () -> channelConfigProvider.load(99999L, "sms")))
                .isPresent()
                .get().extracting(m -> m.get("apiKey")).isEqualTo("iso-only-key");
        // 租户 1 的 API 列表看不到 99999 的配置
        try {
            mvc.perform(get("/api/channel-configs").header(AUTH, "Bearer " + token))
                    .andExpect(jsonPath("$.data.length()").value(0));
        } catch (Exception e) {
            throw new AssertionError("租户 1 不应看到其他租户通道配置", e);
        }
    }

    private <T> T inTenant(Long tenantId, Supplier<T> action) {
        TenantContext.set(new TenantInfo(tenantId));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Long tenantId, Runnable action) {
        TenantContext.set(new TenantInfo(tenantId));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }
}