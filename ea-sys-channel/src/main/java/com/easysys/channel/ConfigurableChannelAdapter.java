package com.easysys.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 真实通道适配器基类：发送前按租户取凭据。
 * 未配置凭据 → 降级为 console 日志下发（channelMessageId 保留 "console-" 前缀，测试契约）。
 * 凭据就绪 → 由子类 doSend 执行真实下发；失败返回 SendResult(false, null, 原因)。
 */
public abstract class ConfigurableChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableChannelAdapter.class);

    private final ChannelConfigProvider provider;

    protected ConfigurableChannelAdapter(ChannelConfigProvider provider) {
        this.provider = provider;
    }

    @Override
    public SendResult send(SendRequest request) {
        Optional<Map<String, String>> cfg = provider.load(request.tenantId(), channel());
        if (cfg.isEmpty()) {
            log.warn("[channel:{}] tenant={} 未配置通道凭据，降级为 console 日志下发", channel(), request.tenantId());
            log.info("[console-channel:{}] tenant={} contact={} execution={} node={} templateId={} idempotencyKey={} content={}",
                    channel(), request.tenantId(), request.contactId(), request.executionId(),
                    request.nodeKey(), request.templateId(), request.idempotencyKey(), request.content());
            return new SendResult(true, "console-" + UUID.randomUUID(), null);
        }
        long start = System.currentTimeMillis();
        log.info("[channel:{}] tenant={} contact={} execution={} node={} templateId={} idempotencyKey={} 收件地址={} 开始真实下发",
                channel(), request.tenantId(), request.contactId(), request.executionId(),
                request.nodeKey(), request.templateId(), request.idempotencyKey(), request.channelAddress());
        SendResult result = doSend(cfg.get(), request);
        log.info("[channel:{}] tenant={} execution={} idempotencyKey={} 下发结果 success={} msgId={} error={} 耗时={}ms",
                channel(), request.tenantId(), request.executionId(), request.idempotencyKey(),
                result.success(), result.channelMessageId(), result.error(), System.currentTimeMillis() - start);
        return result;
    }

    /** 凭据已就绪时的真实下发；失败必须返回 SendResult(false, null, 原因)。 */
    protected abstract SendResult doSend(Map<String, String> cfg, SendRequest request);

    /** 邮件/短信为同步投递，无异步回执查询：一律视为已送达。 */
    @Override
    public Status queryStatus(String channelMessageId) {
        return Status.DELIVERED;
    }
}