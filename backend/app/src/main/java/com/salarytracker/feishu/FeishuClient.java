package com.salarytracker.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书开放平台的三个调用：换 tenant_access_token、回消息、发消息。
 *
 * <p>刻意不引官方 SDK：这里只需要 3 个 HTTP 接口，自己发请求更容易看清失败原因，
 * 也不用为了一个依赖调整构建。</p>
 */
@Component
public class FeishuClient {
    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);
    private static final String BASE = "https://open.feishu.cn/open-apis";
    private static final long REFRESH_AHEAD_SECONDS = 300;

    private final FeishuConfig config;
    private final ObjectMapper mapper;
    private final RestClient client;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public FeishuClient(FeishuConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 回一条消息（引用用户那条），返回 message_id；失败抛异常由调用方记日志。 */
    public String replyCard(String messageId, ObjectNode card) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "interactive");
        body.put("content", json(card));
        JsonNode node = post("/im/v1/messages/" + messageId + "/reply", body);
        return node.path("data").path("message_id").asText("");
    }

    /** 直接发给某个用户（主动推送用）。 */
    public String sendCard(String openId, ObjectNode card) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", openId);
        body.put("msg_type", "interactive");
        body.put("content", json(card));
        JsonNode node = post("/im/v1/messages?receive_id_type=open_id", body);
        return node.path("data").path("message_id").asText("");
    }

    public String replyText(String messageId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "text");
        body.put("content", json(Map.of("text", text)));
        JsonNode node = post("/im/v1/messages/" + messageId + "/reply", body);
        return node.path("data").path("message_id").asText("");
    }

    /**
     * 用 PATCH 覆盖一条已发卡片（卡片按钮点完原地变样，用户不用再跳网页）。
     * 失败不影响主流程，调用方吞掉异常即可。
     */
    public void patchCard(String messageId, ObjectNode card) {
        String accessToken = tenantAccessToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", json(card));
        JsonNode node = client.patch().uri(BASE + "/im/v1/messages/" + messageId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(json(body))
                .retrieve()
                .body(JsonNode.class);
        check(node, "更新卡片");
    }

    synchronized String tenantAccessToken() {
        if (!config.ready()) throw new IllegalStateException("飞书应用凭证未配置");
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) return token;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", config.appId());
        body.put("app_secret", config.appSecret());
        JsonNode node = client.post().uri(BASE + "/auth/v3/tenant_access_token/internal")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(json(body))
                .retrieve()
                .body(JsonNode.class);
        check(node, "获取 tenant_access_token");
        String value = node.path("tenant_access_token").asText("");
        if (value.isEmpty()) throw new IllegalStateException("飞书未返回 tenant_access_token");
        long expire = node.path("expire").asLong(7200);
        token = value;
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expire - REFRESH_AHEAD_SECONDS));
        log.info("飞书 tenant_access_token 已刷新，{} 秒后过期", expire);
        return token;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        String accessToken = tenantAccessToken();
        JsonNode node = client.post().uri(BASE + path)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(json(body))
                .retrieve()
                .body(JsonNode.class);
        check(node, path);
        return node == null ? mapper.createObjectNode() : node;
    }

    private void check(JsonNode node, String action) {
        if (node == null) throw new IllegalStateException(action + "：飞书无响应");
        int code = node.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException(action + " 失败 " + code + "：" + node.path("msg").asText(""));
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("序列化失败", exception);
        }
    }
}
