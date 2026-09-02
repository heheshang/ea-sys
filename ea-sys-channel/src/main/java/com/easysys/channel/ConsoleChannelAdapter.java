package com.easysys.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 开发/联调通道适配器：日志代替真实供应商下发，回执直接置 DELIVERED。
 * 真实供应商（阿里云/腾讯云短信、SMTP/SES 邮件）接入时新增同名 channel 适配器，
 * 由 {@code ChannelRouter} 按 channel() 路由即可替换，无需改动引擎。
 */
public final class ConsoleChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleChannelAdapter.class);

    private final String id;

    public ConsoleChannelAdapter(String id) {
        this.id = id;
    }

    @Override
    public String channel() {
        return id;
    }

    @Override
    public SendResult send(SendRequest request) {
        // 幂等键由引擎生成（executionId:nodeKey:contactId），日志留痕便于人工核对重试去重
        log.info("[console-channel:{}] idempotencyKey={} tenant={} contact={} execution={} node={} content={}",
                id, request.idempotencyKey(), request.tenantId(), request.contactId(),
                request.executionId(), request.nodeKey(), request.content());
        return new SendResult(true, "console-" + UUID.randomUUID(), null);
    }

    @Override
    public Status queryStatus(String channelMessageId) {
        // 本地通道无异步回执，视为立即送达
        return Status.DELIVERED;
    }
}