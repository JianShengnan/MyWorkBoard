package com.salarytracker.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bark（iOS）：装 App 拿 device key，服务端默认 https://api.day.app，自建可改。 */
@Component
public class BarkSender implements PushSender {
    private static final String DEFAULT_SERVER = "https://api.day.app";

    private final RestClient client = PushHttp.client();
    private final ObjectMapper mapper;

    public BarkSender(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "BARK";
    }

    @Override
    public boolean ready(Map<String, String> config) {
        return !PushHttp.text(config == null ? null : config.get("key")).isEmpty();
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message) throws Exception {
        send(config, message, "TEST");
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message, String event) throws Exception {
        String key = PushHttp.text(config.get("key"));
        if (key.isEmpty()) throw new IllegalStateException("未填写 Bark Device Key");
        String server = PushHttp.text(config.get("server"));
        if (server.isEmpty()) server = DEFAULT_SERVER;
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            throw new IllegalStateException("Bark 服务器地址需要以 http(s):// 开头");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_key", key);
        body.put("title", PushHttp.text(message.title()));
        // iOS 通知会显示副标题，用事件类型 + 清单这类一眼可见的定位信息
        String kicker = PushHttp.text(message.kicker());
        if (!kicker.isEmpty()) body.put("subtitle", kicker);
        body.put("body", detail(message));
        body.put("group", "WorkBoard · " + PushText.eventLabel(event));
        if (!PushHttp.text(message.link()).isEmpty()) {
            body.put("url", message.link());
            body.put("autoCopy", "1");
        }

        String response = client.post().uri(server + "/push")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(mapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        JsonNode node = mapper.readTree(response == null || response.isBlank() ? "{}" : response);
        int code = node.path("code").asInt(200);
        if (code != 200) throw new IllegalStateException("Bark 返回 " + code + "："
                + PushHttp.truncate(node.path("message").asText(node.toString()), 180));
    }

    /** Bark 正文没有排版能力，把字段压成「截止 今天 18:00 · 清单 工作」这种单行 */
    private String detail(PushModels.Message message) {
        StringBuilder text = new StringBuilder(PushHttp.text(message.body()));
        for (PushModels.Fact fact : message.facts()) {
            if (text.length() > 0) text.append('\n');
            text.append(fact.label()).append(' ').append(PushText.oneLine(fact.value(), 40));
        }
        for (String line : message.lines()) {
            if (text.length() > 0) text.append('\n');
            text.append("• ").append(PushHttp.text(line));
        }
        return PushHttp.truncate(text.toString(), 400);
    }
}
