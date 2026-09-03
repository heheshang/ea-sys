package com.easysys.notify.dto;

/**
 * 主服务投递请求：受理成功后把真正触达任务交给回调服务（notify）。
 */
public record DeliverRequest(
        Long tenantId,
        Long executionId,
        Long contactId,
        String nodeKey,
        String channel,
        Long templateId,
        String content,
        String channelMsgId,
        String channelAddress) {
}