package com.easysys.channel;

import java.util.Map;
import java.util.Optional;

/**
 * 通道凭据提供方：按租户 + 通道返回已解密配置键值对。
 * 实现在 api 模块（读 channel_config 表，AES 解密）；接口放 channel 模块避免模块依赖环。
 * 返回空 = 该租户未配置（适配器降级 console 日志）。
 */
public interface ChannelConfigProvider {

    Optional<Map<String, String>> load(Long tenantId, String channel);
}