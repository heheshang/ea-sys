package com.easysys.channel;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.util.Map;
import java.util.UUID;

/**
 * 邮件通道：SMTP 真实投递。凭据字段：
 * smtpHost（缺省 localhost）、smtpPort（缺省 25）、smtpUsername/smtpPassword（可选，配了则开启 AUTH）、
 * fromEmail（必填）、starttls（缺省 true，仅对支持方启用）。
 * 收件人为 SendRequest.channelAddress()（联系人 email）。
 */
public final class SmtpEmailChannelAdapter extends ConfigurableChannelAdapter {

    public SmtpEmailChannelAdapter(ChannelConfigProvider provider) {
        super(provider);
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    protected SendResult doSend(Map<String, String> cfg, SendRequest request) {
        String to = request.channelAddress();
        if (to == null || to.isBlank()) {
            return new SendResult(false, null, "联系人缺少邮箱(email)");
        }
        String from = cfg.get("fromEmail");
        if (from == null || from.isBlank()) {
            return new SendResult(false, null, "邮件通道未配置 fromEmail");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.getOrDefault("smtpHost", "localhost"));
        sender.setPort(parsePort(cfg.get("smtpPort")));
        sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "10000");
        sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "10000");
        String user = cfg.get("smtpUsername");
        if (user != null && !user.isBlank()) {
            sender.setUsername(user);
            sender.setPassword(cfg.getOrDefault("smtpPassword", ""));
            sender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
        }
        if (Boolean.parseBoolean(cfg.getOrDefault("starttls", "true"))) {
            sender.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "true");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject("ea-sys 触达通知");
            helper.setText(request.content(), false);
            sender.send(message);
            return new SendResult(true, "smtp-" + UUID.randomUUID(), null);
        } catch (Exception e) {
            return new SendResult(false, null, "邮件发送失败: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static int parsePort(String port) {
        if (port == null || port.isBlank()) {
            return 25;
        }
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            return 25;
        }
    }
}