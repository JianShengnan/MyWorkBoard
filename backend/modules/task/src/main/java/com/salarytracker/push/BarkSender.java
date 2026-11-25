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
        body.put("body", PushHttp.text(message.body()));
        body.put("group", "WorkBoard");
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
}
