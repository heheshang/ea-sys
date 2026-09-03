package com.easysys.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 触达回调服务（M7）：接收真实通道（短信/微信/邮箱）的异步投递回执，
 * 确认「真正触达」后回调主服务更新 delivery_record。
 *
 * dev 环境由本服务模拟真正投递与回执（延迟 + 成败判定），生产替换为真实供应商 webhook。
 */
@SpringBootApplication
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}