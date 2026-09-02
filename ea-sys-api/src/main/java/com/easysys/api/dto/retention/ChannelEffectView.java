package com.easysys.api.dto.retention;

import java.util.List;

/**
 * 渠道效果：每渠道送达率（SENT/总数）与触达人数。
 */
public record ChannelEffectView(
        List<ChannelEffectItem> channels) {

    public record ChannelEffectItem(
            String channel,
            long total,
            long sent,
            long failed,
            long distinctContacts,
            double deliveryRate) {
    }
}