package com.easysys.api.dto.workflow;

import java.time.Instant;

/**
 * 通道触达日志行（delivery_record）：每次真实下发的通道级记录。
 * contactName 取自联系人外部 ID，便于触达监控识别。
 */
public record DeliveryLogView(
        Long id,
        Long executionId,
        Long contactId,
        String contactName,
        String channel,
        Long templateId,
        String content,
        String channelMsgId,
        String status,
        String error,
        Instant createdAt) {
}