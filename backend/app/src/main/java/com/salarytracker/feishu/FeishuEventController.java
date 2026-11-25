package com.salarytracker.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书事件回调入口（控制台「事件配置」里填的请求地址）。
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li>保存地址时飞书会先发一条 {@code url_verification}，必须 1 秒内原样返回 challenge，
 *       否则地址根本存不进去；配了 Encrypt Key 时这条请求也是加密的。</li>
 *   <li>事件重推很常见（超时或非 2xx），按 {@code event_id} 去重，否则一句话会跑两轮 AI。</li>
 *   <li>必须在 3 秒内响应，所以真正的处理丢给后台线程，这里只做验签、解密、去重、入队。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/feishu")
public class FeishuEventController {
    private static final Logger log = LoggerFactory.getLogger(FeishuEventController.class);

    private final FeishuConfig config;
    private final ObjectMapper mapper;
    private final FeishuBotService bot;
    private final JdbcTemplate jdbc;

    public FeishuEventController(FeishuConfig config, ObjectMapper mapper, FeishuBotService bot, JdbcTemplate jdbc) {
        this.config = config;
        this.mapper = mapper;
        this.bot = bot;
        this.jdbc = jdbc;
    }

    @PostMapping(value = "/event", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> event(
            @RequestBody(required = false) String raw,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce) {
        String body = raw == null ? "" : raw;
        try {
            if (!signatureValid(body, signature, timestamp, nonce)) {
                log.warn("飞书回调验签失败，已拒绝");
                return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
            }
            JsonNode payload = unwrap(body);
            if (payload == null) {
                log.warn("飞书回调内容为空");
                return ResponseEntity.ok(Map.of("code", 0));
            }
            if ("url_verification".equals(payload.path("type").asText())) {
                return ResponseEntity.ok(Map.of("challenge", payload.path("challenge").asText("")));
            }
            if (!tokenValid(payload)) {
                log.warn("飞书回调 Verification Token 不匹配，已拒绝");
                return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
            }
            onEvent(payload);
            return ResponseEntity.ok(Map.of("code", 0));
        } catch (IllegalArgumentException exception) {
            log.warn("飞书回调无法解析: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            log.warn("飞书回调处理异常: {}", exception.getMessage());
            // 返回 200 会让飞书不再重推；这里刻意返回 200，避免同一条消息被反复重试刷屏
            return ResponseEntity.ok(Map.of("code", 0));
        }
    }

    /** 卡片按钮回传。第一版只做 toast 反馈与动作记录，卡片原地更新在下一批。 */
    @PostMapping(value = "/card", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> card(
            @RequestBody(required = false) String raw,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce) {
        String body = raw == null ? "" : raw;
        if (!signatureValid(body, signature, timestamp, nonce)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
        }
        JsonNode payload;
        try {
            payload = unwrap(body);
        } catch (Exception exception) {
            log.warn("飞书卡片回调无法解析: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid payload"));
        }
        if (payload == null) return ResponseEntity.ok(Map.of("toast", toast("info", "收到")));
        String openId = payload.path("open_id").asText("");
        JsonNode action = payload.path("action");
        String name = action.path("value").path("action").asText("");
        String value = action.path("value").path("payload").asText("");
        jdbc.update("INSERT INTO feishu_card_action(open_id,action,payload,result) VALUES(?,?,?,?)",
                openId, name, value, "ACK");
        log.info("飞书卡片回调 openId={} action={}", openId, name);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("toast", toast("success", "已收到"));
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toast(String type, String content) {
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("type", type);
        toast.put("content", content);
        return toast;
    }

    private void onEvent(JsonNode payload) throws Exception {
        JsonNode header = payload.path("header");
        String eventType = header.path("event_type").asText("");
        String eventId = header.path("event_id").asText("");
        if (!"im.message.receive_v1".equals(eventType)) {
            log.debug("忽略飞书事件 {}", eventType);
            return;
        }
        if (!eventId.isEmpty()) {
            int inserted = jdbc.update("INSERT IGNORE INTO feishu_event(event_id,event_type) VALUES(?,?)",
                    eventId, eventType);
            if (inserted == 0) {
                log.info("飞书事件重复推送，已跳过 eventId={}", eventId);
                return;
            }
        }
        JsonNode message = payload.path("event").path("message");
        String type = message.path("message_type").asText("");
        String openId = payload.path("event").path("sender").path("sender_id").path("open_id").asText("");
        String unionId = payload.path("event").path("sender").path("sender_id").path("union_id").asText("");
        String messageId = message.path("message_id").asText("");
        String chatId = message.path("chat_id").asText("");
        String chatType = message.path("chat_type").asText("");
        if (openId.isEmpty() || messageId.isEmpty()) return;
        if (!"text".equals(type)) {
            bot.submit(new FeishuBotService.Incoming(openId, unionId, chatId, chatType, messageId,
                    "[非文本消息]"));
            return;
        }
        String text = mapper.readTree(message.path("content").asText("{}")).path("text").asText("");
        bot.submit(new FeishuBotService.Incoming(openId, unionId, chatId, chatType, messageId, text));
    }

    private boolean signatureValid(String body, String signature, String timestamp, String nonce) {
        // 没配 Encrypt Key 时飞书不下发签名头，此时由 Verification Token 兜底
        if (!config.encrypted()) return true;
        if (signature == null || signature.isBlank()) return false;
        return FeishuCrypto.signatureValid(timestamp == null ? "" : timestamp, nonce == null ? "" : nonce,
                config.encryptKey(), body, signature);
    }

    private boolean tokenValid(JsonNode payload) {
        String expected = config.verificationToken();
        if (expected.isEmpty()) return true;
        String token = payload.path("header").path("token").asText(payload.path("token").asText(""));
        return expected.equals(token);
    }

    /** 配了 Encrypt Key 时整个 body 是 {@code {"encrypt":"..."}}，解出来才是真正的事件。 */
    private JsonNode unwrap(String body) throws Exception {
        if (body.isBlank()) return null;
        JsonNode node = mapper.readTree(body);
        String encrypted = node.path("encrypt").asText("");
        if (encrypted.isEmpty()) return node;
        if (!config.encrypted()) throw new IllegalArgumentException("收到加密回调但未配置 Encrypt Key");
        return mapper.readTree(FeishuCrypto.decrypt(config.encryptKey(), encrypted));
    }
}
