package com.easysys.engine.service;

import com.easysys.engine.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 回调服务（ea-sys-notify）投递客户端：受理成功后把真正触达任务交给回调服务异步执行。
 * notify 收到通道回执后回调主服务更新 delivery_record（SENT → DELIVERED/FAILED）。
 */
@Component
public class DeliveryNotifier {

    private static final Logger log = LoggerFactory.getLogger(DeliveryNotifier.class);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public DeliveryNotifier(@Value("${easysys.notify.base-url:http://localhost:8092}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 通知回调服务真正触达。同步等待受理（短超时）；false = notify 不可达，由调用方标记 FAILED。
     */
    public boolean deliver(Long tenantId, Long executionId, Long contactId, String nodeKey,
                           String channel, Long templateId, String content, String channelMsgId,
                           String channelAddress) {
        String body = "{\"tenantId\":" + tenantId
                + ",\"executionId\":" + executionId
                + ",\"contactId\":" + contactId
                + ",\"nodeKey\":\"" + escape(nodeKey) + "\""
                + ",\"channel\":\"" + escape(channel) + "\""
                + ",\"templateId\":" + templateId
                + ",\"content\":\"" + escape(content == null ? "" : content) + "\""
                + ",\"channelMsgId\":\"" + escape(channelMsgId) + "\""
                + (channelAddress == null ? "" : ",\"channelAddress\":\"" + escape(channelAddress) + "\"")
                + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/notify/deliver"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                return true;
            }
            log.error("notify 受理失败：channelMsgId={} http={} body={}", channelMsgId, resp.statusCode(), resp.body());
            return false;
        } catch (Exception e) {
            log.error("notify 不可达：channelMsgId={} url={}", channelMsgId, baseUrl, e);
            return false;
        }
    }

    private static String escape(String s) {
        // JSON 字符串转义：除引号/反斜杠外，控制字符（\n \r \t 等）也必须转义，否则 body 非法被 400
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}