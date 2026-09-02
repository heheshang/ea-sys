package com.easysys.channel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通道适配器装配：sms（通用 HTTP 短信）/ email（SMTP 邮件）/ wechat（微信模板消息）。
 * 真实供应商凭据经 {@link ChannelConfigProvider} 按租户注入（api 模块读 channel_config 表）：
 * 发送前由 {@link ConfigurableChannelAdapter} 取凭据，未配置时降级 console 日志下发（channelMessageId 前缀 "console-"）。
 */
@Configuration
public class ChannelConfig {

    @Bean
    public SmtpEmailChannelAdapter emailChannelAdapter(ChannelConfigProvider provider) {
        return new SmtpEmailChannelAdapter(provider);
    }

    @Bean
    public HttpSmsChannelAdapter smsChannelAdapter(ChannelConfigProvider provider) {
        return new HttpSmsChannelAdapter(provider);
    }

    @Bean
    public WechatChannelAdapter wechatChannelAdapter(ChannelConfigProvider provider) {
        return new WechatChannelAdapter(provider);
    }
}