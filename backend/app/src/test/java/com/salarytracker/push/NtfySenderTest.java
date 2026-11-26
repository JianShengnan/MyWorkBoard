package com.salarytracker.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ntfy 通道：请求要落到 /{topic}，正文结构要能被 ntfy 客户端正确渲染。
 * 用本地 HttpServer 接住请求，避免依赖外网。
 */
class NtfySenderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final NtfySender sender = new NtfySender(mapper);
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"x\",\"event\":\"message\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readyRequiresTopic() {
        assertFalse(sender.ready(Map.of()), "没填主题时通道应视为未就绪");
        assertFalse(sender.ready(null));
        assertTrue(sender.ready(Map.of("topic", "workboard-test")));
    }

    @Test
    void publishesJsonToTopicWithToken() throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("server", "http://127.0.0.1:" + server.getAddress().getPort());
        config.put("topic", "workboard-test");
        config.put("token", "tk_test");

        PushModels.Message message = new PushModels.Message(
                "写周报", "工作 · 今天 18:00", "还有 2 个检查项",
                "https://jsn1024.cn/tasks", null, List.of(), List.of(), List.of());
        sender.send(config, message, "TASK_REMINDER");

        assertEquals("/", path.get(), "JSON 发布要 POST 到根路径，主题写在 body 里");
        assertEquals("Bearer tk_test", authorization.get(), "填了令牌就必须带 Authorization");

        JsonNode json = mapper.readTree(body.get());
        assertEquals("workboard-test", json.path("topic").asText(), "JSON 发布必须带 topic 字段，否则 ntfy 会当纯文本");
        assertEquals("写周报", json.path("title").asText());
        assertEquals(4, json.path("priority").asInt(), "任务提醒应走高优先级");
        assertEquals("https://jsn1024.cn/tasks", json.path("click").asText());
        String text = json.path("message").asText();
        assertTrue(text.contains("工作 · 今天 18:00"), "副标题要并进正文首行：" + text);
        assertTrue(text.contains("还有 2 个检查项"));
    }

    @Test
    void digestUsesNormalPriority() throws Exception {
        Map<String, String> config = Map.of(
                "server", "http://127.0.0.1:" + server.getAddress().getPort(),
                "topic", "workboard-test");
        sender.send(config, new PushModels.Message("今日习惯", "3 项未打卡", null), "HABIT_DIGEST");

        JsonNode json = mapper.readTree(body.get());
        assertEquals(3, json.path("priority").asInt(), "汇总类不该半夜弹横幅");
        assertTrue(authorization.get() == null, "没填令牌时不应带 Authorization 头");
    }
}
