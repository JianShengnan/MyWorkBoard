package com.salarytracker.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书自定义机器人。
 * 只填 Webhook 即可用；开启「签名校验」时再填 secret，按官方算法补 timestamp/sign。
 *
 * <p>消息以「消息卡片」发送（msg_type=interactive），标题、字段、正文、按钮分开排版；
 * 卡片 JSON 拼错时飞书会返回非 0 业务码，因此这里不额外做本地校验。</p>
 */
@Component
public class FeishuSender implements PushSender {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final RestClient client = PushHttp.client();
    private final ObjectMapper mapper;

    public FeishuSender(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "FEISHU";
    }

    @Override
    public boolean ready(Map<String, String> config) {
        String webhook = PushHttp.text(config == null ? null : config.get("webhook"));
        return webhook.startsWith("http://") || webhook.startsWith("https://");
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message) throws Exception {
        send(config, message, "TEST");
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message, String event) throws Exception {
        String webhook = PushHttp.text(config.get("webhook"));
        if (!ready(config)) throw new IllegalStateException("未填写飞书 Webhook 地址");

        String footer = "WorkBoard · " + LocalDateTime.now().format(STAMP);
        ObjectNode card = FeishuCard.build(mapper, message, event, footer);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "interactive");
        body.put("card", card);
        String secret = PushHttp.text(config.get("secret"));
        if (!secret.isEmpty()) {
            long timestamp = System.currentTimeMillis() / 1000;
            body.put("timestamp", String.valueOf(timestamp));
            body.put("sign", sign(timestamp, secret));
        }

        String response = client.post().uri(webhook)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(mapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        JsonNode node = mapper.readTree(response == null || response.isBlank() ? "{}" : response);
        // 新接口返回 code，老接口返回 StatusCode；HTTP 200 但业务码非 0 同样算失败
        int code = node.has("code") ? node.get("code").asInt(0) : node.path("StatusCode").asInt(0);
        if (code != 0) throw new IllegalStateException("飞书返回 " + code + "："
                + PushHttp.truncate(node.path("msg").asText(node.path("StatusMessage").asText(node.toString())), 180));
    }

    /**
     * 飞书签名（官方示例的等价实现）：stringToSign 作为 HMAC 的 key，待签名内容为空，
     * 取 HMAC-SHA256 摘要后 Base64。注意不是「secret 当 key、stringToSign 当 data」。
     */
    private String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }
}
