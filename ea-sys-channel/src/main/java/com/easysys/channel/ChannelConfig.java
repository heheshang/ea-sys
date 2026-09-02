package com.easysys.channel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通道适配器装配：sms / email 本地日志通道。
 * 多租户凭据注入（Jasypt 加密）在真实供应商接入时追加，此处仅声明通道标识。
 */
@Configuration
public class ChannelConfig {

    @Bean
    public ConsoleChannelAdapter smsChannelAdapter() {
        return new ConsoleChannelAdapter("sms");
    }

    @Bean
    public ConsoleChannelAdapter emailChannelAdapter() {
        return new ConsoleChannelAdapter("email");
    }
}