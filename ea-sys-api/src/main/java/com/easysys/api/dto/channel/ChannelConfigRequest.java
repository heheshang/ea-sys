package com.easysys.api.dto.channel;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** 通道配置保存请求。config 键值示例（sms）：endpoint/apiKey/apiSecret/signName；（email）：smtpHost/smtpPort/smtpUsername/smtpPassword/fromEmail。 */
public record ChannelConfigRequest(@NotNull Map<String, String> config, Boolean enabled) {
}