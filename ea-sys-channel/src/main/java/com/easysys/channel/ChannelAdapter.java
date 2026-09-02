package com.easysys.channel;

/**
 * 通道适配 SPI（M3 实现，微信未接入但 SPI 预留扩展位）。
 * 实现类须注册为 Spring Bean 并通过 {@link #channel()} 声明通道标识。
 */
public interface ChannelAdapter {

    /** 通道标识：sms / email / push / wechat */
    String channel();

    /** 发送触达；idempotencyKey 由引擎生成，实现须支持按此幂等去重 */
    SendResult send(SendRequest request);

    /** 查询回执状态 */
    Status queryStatus(String channelMessageId);

    record SendRequest(Long tenantId, Long contactId, Long executionId, String nodeKey,
                       String templateId, String content, String idempotencyKey) {
    }

    record SendResult(boolean success, String channelMessageId, String error) {
    }

    enum Status {
        SENT, DELIVERED, FAILED, UNKNOWN
    }
}