package com.salarytracker.push;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
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
        send(config, message, "TEST");
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message, String event) throws Exception {
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
        mail.setSentDate(new Date());

        MimeBodyPart text = new MimeBodyPart();
        text.setText(plain(message), StandardCharsets.UTF_8.name());
        MimeBodyPart html = new MimeBodyPart();
        html.setContent(page(message, event), "text/html; charset=UTF-8");
        MimeMultipart multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(text);
        multipart.addBodyPart(html);
        mail.setContent(multipart);
        Transport.send(mail);
    }

    private String plain(PushModels.Message message) {
        StringBuilder text = new StringBuilder(PushHttp.text(message.body()));
        for (PushModels.Fact fact : message.facts()) {
            if (text.length() > 0) text.append('\n');
            text.append(fact.label()).append("：").append(fact.value());
        }
        for (String line : message.lines()) {
            text.append("\n· ").append(PushHttp.text(line));
        }
        if (!PushHttp.text(message.link()).isEmpty()) text.append("\n\n").append(message.link());
        text.append("\n\n—— 来自 WorkBoard 提醒");
        return text.toString();
    }

    /** 邮件适合排版，把字段做成两列表格 + 主按钮，和飞书卡片同一套信息 */
    private String page(PushModels.Message message, String event) {
        String accent = switch (PushText.template(event)) {
            case "blue" -> "#2b6cf6";
            case "orange" -> "#d97706";
            case "turquoise" -> "#0f9488";
            case "green" -> "#16a34a";
            default -> "#475569";
        };
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font:14px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#0f172a;max-width:560px\">");
        html.append("<div style=\"font-size:12px;color:#64748b;margin-bottom:4px\">")
                .append(escape(PushText.eventLabel(event)))
                .append(PushHttp.text(message.kicker()).isEmpty() ? "" : " · " + escape(message.kicker()))
                .append("</div>");
        html.append("<div style=\"font-size:19px;font-weight:700;color:").append(accent).append(";margin-bottom:12px\">")
                .append(escape(PushHttp.text(message.title()))).append("</div>");
        if (!PushHttp.text(message.body()).isEmpty()) {
            html.append("<p style=\"margin:0 0 12px;color:#334155\">")
                    .append(escape(PushHttp.text(message.body()))).append("</p>");
        }
        if (!message.facts().isEmpty()) {
            html.append("<table style=\"border-collapse:collapse;margin:0 0 12px;width:100%\">");
            for (PushModels.Fact fact : message.facts()) {
                html.append("<tr><td style=\"padding:4px 12px 4px 0;color:#64748b;white-space:nowrap\">")
                        .append(escape(fact.label())).append("</td><td style=\"padding:4px 0;color:#0f172a\">")
                        .append(escape(fact.value())).append("</td></tr>");
            }
            html.append("</table>");
        }
        if (!message.lines().isEmpty()) {
            html.append("<ul style=\"margin:0 0 14px;padding-left:18px;color:#334155\">");
            for (String line : message.lines()) {
                html.append("<li>").append(escape(PushHttp.text(line))).append("</li>");
            }
            html.append("</ul>");
        }
        if (!PushHttp.text(message.link()).isEmpty()) {
            String label = PushHttp.text(message.linkLabel());
            html.append("<a href=\"").append(escape(message.link()))
                    .append("\" style=\"display:inline-block;padding:9px 16px;border-radius:8px;background:")
                    .append(accent).append(";color:#fff;text-decoration:none;font-weight:600\">")
                    .append(escape(label.isEmpty() ? "在 WorkBoard 打开" : label)).append("</a>");
        }
        html.append("<div style=\"margin-top:16px;font-size:12px;color:#94a3b8\">WorkBoard 提醒 · 可在「设置 → 推送提醒」调整</div>");
        html.append("</div>");
        return html.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
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
