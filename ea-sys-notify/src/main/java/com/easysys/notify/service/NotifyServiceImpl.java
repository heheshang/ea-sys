package com.easysys.notify.service;

import com.easysys.notify.dto.DeliverRequest;
import com.easysys.notify.dto.ReceiptRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通道回执入站服务（dev 实现）：
 * - deliver：登记待确认触达 → 模拟真正投递（延迟 simulate-delay-ms，内容含失败关键词 → FAILED，否则 DELIVERED）
 *   → 视同收到通道异步回执 → 回调主服务更新 delivery_record。
 * - receipt：真实供应商 webhook 入口（生产替换模拟路径），确认后回调主服务。
 *
 * 回调鉴权：X-Internal-Token 头，与主服务 easysys.notify.callback-token 一致。
 */
@Service
public class NotifyServiceImpl implements NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyServiceImpl.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 已登记待确认的触达（channelMsgId → tenantId；幂等；回执后移除）。 */
    private final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final String callbackUrl;
    private final String callbackToken;
    private final long simulateDelayMs;
    private final String failKeyword;

    public NotifyServiceImpl(
            @Value("${easysys.notify.callback-url}") String callbackUrl,
            @Value("${easysys.notify.callback-token}") String callbackToken,
            @Value("${easysys.notify.simulate-delay-ms:500}") long simulateDelayMs,
            @Value("${easysys.notify.simulate-fail-keyword:触发失败内容}") String failKeyword) {
        this.callbackUrl = callbackUrl;
        this.callbackToken = callbackToken;
        this.simulateDelayMs = simulateDelayMs;
        this.failKeyword = failKeyword;
    }

    @Override
    public void deliver(DeliverRequest req) {
        if (req.channelMsgId() == null || req.channelMsgId().isBlank()) {
            log.warn("deliver 忽略：channelMsgId 为空 executionId={} contactId={}", req.executionId(), req.contactId());
            return;
        }
        if (pending.putIfAbsent(req.channelMsgId(), req.tenantId()) != null) {
            log.debug("deliver 幂等：channelMsgId={} 已登记", req.channelMsgId());
            return;
        }
        log.info("受理真正触达：channelMsgId={} channel={} contactId={} executionId={}",
                req.channelMsgId(), req.channel(), req.contactId(), req.executionId());
        executor.submit(() -> {
            try {
                // 模拟真实通道投递耗时（dev；生产此处替换为真实供应商发送 + 等待其 webhook 回调 receipt）
                Thread.sleep(simulateDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String status = req.content() != null && req.content().contains(failKeyword) ? "FAILED" : "DELIVERED";
            String error = "FAILED".equals(status) ? "通道回执：模拟投递失败（含失败触发词）" : null;
            receipt(new ReceiptRequest(req.channelMsgId(), status, error));
        });
    }

    @Override
    public void receipt(ReceiptRequest receipt) {
        if (receipt.channelMsgId() == null || receipt.channelMsgId().isBlank()) {
            log.warn("receipt 忽略：channelMsgId 为空");
            return;
        }
        Long tenantId = pending.remove(receipt.channelMsgId());
        if (tenantId == null) {
            log.warn("receipt 忽略：channelMsgId={} 未登记 tenantId", receipt.channelMsgId());
            return;
        }
        log.info("收到通道异步回执：channelMsgId={} status={} error={}",
                receipt.channelMsgId(), receipt.status(), receipt.error());
        // 回调主服务：确认真正触达
        String body = "{\"channelMsgId\":\"" + receipt.channelMsgId()
                + "\",\"tenantId\":" + tenantId
                + ",\"status\":\"" + receipt.status() + "\""
                + (receipt.error() == null ? "" : ",\"error\":\"" + escape(receipt.error()) + "\"")
                + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(callbackUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", callbackToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                log.info("回调主服务成功：channelMsgId={} status={} http={}",
                        receipt.channelMsgId(), receipt.status(), resp.statusCode());
            } else {
                log.error("回调主服务失败：channelMsgId={} http={} body={}",
                        receipt.channelMsgId(), resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("回调主服务异常：channelMsgId={}", receipt.channelMsgId(), e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}