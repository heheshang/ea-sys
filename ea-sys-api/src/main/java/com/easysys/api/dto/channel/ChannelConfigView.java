package com.easysys.api.dto.channel;

import java.time.Instant;
import java.util.Map;

/** 通道配置视图：config 已脱敏（密码/密钥键值替换为 ******）。 */
public record ChannelConfigView(Long id, String channel, Boolean enabled,
                                Map<String, String> config, Instant updatedAt) {
}