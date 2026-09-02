package com.easysys.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 微信通道：公众号/订阅号模板消息（官方 API，JDK HttpClient，零额外依赖）。
 * 凭据字段：appId（必填）、appSecret（必填）、templateId（必填，微信后台模板 ID）、
 * endpoint（可选，覆盖 API 基址，缺省 https://api.weixin.qq.com，本地 mock/代理联调时覆盖）。
 * 流程：GET /cgi-bin/token 取 access_token（内存缓存，expires_in 提前 5 分钟过期）→
 * POST /cgi-bin/message/template/send，body {touser, template_id, data:{content:{value,color}}}。
 * 成功以协议层 errcode==0 判定（HTTP 2xx 不代表成功）。
 * 供应商协议差异（订阅消息、企业微信、不同模板字段结构）预留 doSend 为扩展点，对接时重写。
 */
public final class WechatChannelAdapter extends ConfigurableChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WechatChannelAdapter.class);

    private static final String DEFAULT_BASE = "https://api.weixin.qq.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** access_token 安全余量（秒）：提前刷新避免过期边界实时失效。 */
    private static final long TOKEN_EXPIRY_MARGIN = 300;

    private final HttpClient http;

    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    public WechatChannelAdapter(ChannelConfigProvider provider) {
        super(provider);
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public String channel() {
        return "wechat";
    }

    @Override
    protected SendResult doSend(Map<String, String> cfg, SendRequest request) {
        String openid = request.channelAddress();
        if (openid == null || openid.isBlank()) {
            return new SendResult(false, null, "联系人缺少微信 openid(wechat)");
        }
        String appId = cfg.get("appId");
        String appSecret = cfg.get("appSecret");
        String templateId = cfg.get("templateId");
        if (isBlank(appId) || isBlank(appSecret) || isBlank(templateId)) {
            return new SendResult(false, null, "微信通道未配置 appId/appSecret/templateId");
        }
        String base = cfg.getOrDefault("endpoint", DEFAULT_BASE);
        String token = getAccessToken(base, appId, appSecret);
        if (token == null) {
            log.warn("[wechat] tenant={} idempotencyKey={} endpoint={} access_token 获取失败", request.tenantId(),
                    request.idempotencyKey(), base);
            return new SendResult(false, null, "微信 access_token 获取失败");
        }
        String body = "{\"touser\":\"" + jsonEscape(openid)
                + "\",\"template_id\":\"" + jsonEscape(templateId)
                + "\",\"data\":{\"content\":{\"value\":\"" + jsonEscape(request.content())
                + "\",\"color\":\"#173177\"}}}";
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/cgi-bin/message/template/send?access_token=" + token))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String respBody = resp.body();
                // 微信协议：HTTP 2xx 仅代表网关可达，业务成败看 errcode
                if (respBody != null && respBody.contains("\"errcode\":0")) {
                    log.info("[wechat] tenant={} idempotencyKey={} endpoint={} errcode=0 发送成功", request.tenantId(),
                            request.idempotencyKey(), base);
                    return new SendResult(true, "wechat-" + UUID.randomUUID(), null);
                }
                log.warn("[wechat] tenant={} idempotencyKey={} endpoint={} 供应商返回 errcode: {}",
                        request.tenantId(), request.idempotencyKey(), base, truncate(respBody));
                return new SendResult(false, null, "微信供应商返回 errcode: " + truncate(respBody));
            }
            log.warn("[wechat] tenant={} idempotencyKey={} endpoint={} status={} 供应商返回: {}", request.tenantId(),
                    request.idempotencyKey(), base, resp.statusCode(), truncate(resp.body()));
            return new SendResult(false, null,
                    "微信供应商返回 " + resp.statusCode() + ": " + truncate(resp.body()));
        } catch (Exception e) {
            log.warn("[wechat] tenant={} idempotencyKey={} endpoint={} 发送异常: {}", request.tenantId(),
                    request.idempotencyKey(), base,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
            return new SendResult(false, null, "微信发送失败: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** 取 access_token：内存缓存过期刷新；失败返回 null（不缓存，下次重试）。 */
    private String getAccessToken(String base, String appId, String appSecret) {
        String cached = accessToken;
        if (cached != null && System.currentTimeMillis() < accessTokenExpiresAt) {
            return cached;
        }
        synchronized (this) {
            if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt) {
                return accessToken;
            }
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(base + "/cgi-bin/token?grant_type=client_credential"
                                        + "&appid=" + enc(appId) + "&secret=" + enc(appSecret)))
                                .timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                String respBody = resp.statusCode() == 200 ? resp.body() : null;
                String token = respBody == null ? null : between(respBody, "\"access_token\":\"", "\"");
                if (token == null || token.isBlank()) {
                    log.warn("[wechat] endpoint={} token 接口响应异常: status={} body={}", base,
                            resp.statusCode(), truncate(respBody));
                    return null;
                }
                String expiresIn = between(respBody, "\"expires_in\":", ",");
                long ttl = expiresIn == null ? 7200 : Long.parseLong(expiresIn.trim());
                accessToken = token;
                accessTokenExpiresAt = System.currentTimeMillis() + (ttl - TOKEN_EXPIRY_MARGIN) * 1000;
                return token;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 简单 JSON 字符串转义：\" \\ \n \r \t。 */
    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        if (from < 0) {
            return null;
        }
        int begin = from + start.length();
        int to = source.indexOf(end, begin);
        if (to < 0) {
            return null;
        }
        return source.substring(begin, to);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}