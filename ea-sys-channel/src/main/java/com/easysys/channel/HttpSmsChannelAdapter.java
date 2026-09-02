package com.easysys.channel;

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
 * 短信通道：通用 HTTP 供应商形态（POST application/x-www-form-urlencoded，JDK HttpClient，零额外依赖）。
 * 凭据字段：endpoint（必填）、apiKey、apiSecret、signName。
 * 请求头携带 X-Api-Key / X-Api-Secret；请求体含 phone / content / idempotencyKey / signName。
 * 供应商协议差异（AKSK 签名、JSON 协议、异步回执查询）预留 doSend 为扩展点，对接具体厂商时重写。
 */
public final class HttpSmsChannelAdapter extends ConfigurableChannelAdapter {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;

    public HttpSmsChannelAdapter(ChannelConfigProvider provider) {
        super(provider);
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public String channel() {
        return "sms";
    }

    @Override
    protected SendResult doSend(Map<String, String> cfg, SendRequest request) {
        String phone = request.channelAddress();
        if (phone == null || phone.isBlank()) {
            return new SendResult(false, null, "联系人缺少手机号(sms)");
        }
        String endpoint = cfg.get("endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            return new SendResult(false, null, "短信通道未配置 endpoint");
        }
        StringBuilder body = new StringBuilder("phone=").append(enc(phone))
                .append("&content=").append(enc(request.content()))
                .append("&idempotencyKey=").append(enc(request.idempotencyKey()));
        String signName = cfg.get("signName");
        if (signName != null && !signName.isBlank()) {
            body.append("&signName=").append(enc(signName));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        String apiKey = cfg.get("apiKey");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-Api-Key", apiKey);
        }
        String apiSecret = cfg.get("apiSecret");
        if (apiSecret != null && !apiSecret.isBlank()) {
            builder.header("X-Api-Secret", apiSecret);
        }
        try {
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                // 供应商响应中的消息 ID / 失败码解析：对接具体厂商时扩展（当前以本地 UUID 记 ID）。
                return new SendResult(true, "sms-" + UUID.randomUUID(), null);
            }
            return new SendResult(false, null,
                    "短信供应商返回 " + resp.statusCode() + ": " + truncate(resp.body()));
        } catch (Exception e) {
            return new SendResult(false, null, "短信发送失败: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}