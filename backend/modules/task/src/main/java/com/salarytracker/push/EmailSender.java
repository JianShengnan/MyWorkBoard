package com.salarytracker.push;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件通道：收件人按用户配置，发件服务器走服务端环境变量（不能让每个用户在网页里填 SMTP 密码）。
 *
 * <pre>
 * PUSH_SMTP_HOST      必填，例如 smtp.qq.com
 * PUSH_SMTP_PORT      默认 465
 * PUSH_SMTP_USERNAME  登录账号（同时作为默认发件人）
 * PUSH_SMTP_PASSWORD  授权码 / 密码
 * PUSH_SMTP_FROM      可选，默认取 USERNAME
 * PUSH_SMTP_SSL       默认 true；false 时用 STARTTLS（587）
 * </pre>
 */
@Component
public class EmailSender implements PushSender {

    @Override
    public String kind() {
        return "EMAIL";
    }

    @Override
    public boolean ready(Map<String, String> config) {
        return !PushHttp.text(config == null ? null : config.get("to")).isEmpty() && !host().isEmpty() && !sender().isEmpty();
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message) throws Exception {
        String to = PushHttp.text(config.get("to"));
        if (to.isEmpty()) throw new IllegalStateException("未填写收件邮箱");
        String host = host();
        if (host.isEmpty()) throw new IllegalStateException("服务端未配置 SMTP（PUSH_SMTP_HOST）");
        String sender = sender();
        if (sender.isEmpty()) throw new IllegalStateException("服务端未配置发件人（PUSH_SMTP_USERNAME / PUSH_SMTP_FROM）");

        boolean ssl = !"false".equalsIgnoreCase(env("PUSH_SMTP_SSL"));
        String port = env("PUSH_SMTP_PORT").isEmpty() ? (ssl ? "465" : "587") : env("PUSH_SMTP_PORT");
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        if (!ssl) props.put("mail.smtp.starttls.enable", "true");

        String username = env("PUSH_SMTP_USERNAME");
        String password = env("PUSH_SMTP_PASSWORD");
        Session session;
        if (!username.isEmpty() && !password.isEmpty()) {
            props.put("mail.smtp.auth", "true");
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
        } else {
            session = Session.getInstance(props);
        }

        MimeMessage mail = new MimeMessage(session);
        mail.setFrom(new InternetAddress(sender));
        mail.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        mail.setSubject(PushHttp.text(message.title()), StandardCharsets.UTF_8.name());
        StringBuilder text = new StringBuilder(PushHttp.text(message.body()));
        if (!PushHttp.text(message.link()).isEmpty()) text.append("\n\n").append(message.link());
        text.append("\n\n—— 来自 WorkBoard 提醒");
        mail.setText(text.toString(), StandardCharsets.UTF_8.name());
        mail.setSentDate(new Date());
        Transport.send(mail);
    }

    private String host() {
        return env("PUSH_SMTP_HOST");
    }

    private String sender() {
        String from = env("PUSH_SMTP_FROM");
        return from.isEmpty() ? env("PUSH_SMTP_USERNAME") : from;
    }

    private String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }
}
