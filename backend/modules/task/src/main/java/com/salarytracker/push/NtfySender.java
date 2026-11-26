package com.salarytracker.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ntfy：Android / 桌面 / 自建服务器都能收，一次 POST 一条 JSON 即可。
 *
 * <p>与 Bark 的分工：Bark 只覆盖 iOS，ntfy 覆盖 Android 与电脑。
 * 主题名即凭证（ntfy 默认不鉴权），所以要提醒用户用足够长的随机串；
 * 自建服务器若开了访问控制，把令牌填到 token 字段。</p>
 */
@Component
public class NtfySender implements PushSender {
    private static final String DEFAULT_SERVER = "https://ntfy.sh";

    private final RestClient client = PushHttp.client();
    private final ObjectMapper mapper;

    public NtfySender(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "NTFY";
    }

    @Override
    public boolean ready(Map<String, String> config) {
        return !PushHttp.text(config == null ? null : config.get("topic")).isEmpty();
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message) throws Exception {
        send(config, message, "TEST");
    }

    @Override
    public void send(Map<String, String> config, PushModels.Message message, String event) throws Exception {
        String topic = PushHttp.text(config.get("topic"));
        if (topic.isEmpty()) throw new IllegalStateException("未填写 ntfy 主题");
        String server = PushHttp.text(config.get("server"));
        if (server.isEmpty()) server = DEFAULT_SERVER;
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            throw new IllegalStateException("ntfy 服务器地址需要以 http(s):// 开头");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // JSON 发布必须把 topic 放在 body 里、POST 到根路径；
        // POST /{topic} 只接受纯文本，塞 JSON 会被当成正文原样显示。
        body.put("topic", topic);
        body.put("title", PushHttp.text(message.title()));
        // ntfy 没有副标题，把「事件 + 清单」这类定位信息并到正文首行
        body.put("message", detail(message));
        body.put("priority", priority(event));
        List<String> tags = new ArrayList<>();
        tags.add(tag(event));
        body.put("tags", tags);
        if (!PushHttp.text(message.link()).isEmpty()) {
            body.put("click", message.link());
        }

        RestClient.RequestBodySpec request = client.post()
                .uri(server + "/")
                .header("Content-Type", "application/json; charset=utf-8");
        String token = PushHttp.text(config.get("token"));
        if (!token.isEmpty()) request = request.header("Authorization", "Bearer " + token);

        request.body(mapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity();
    }

    /** 任务类提醒走高优先级（iOS/Android 会横幅弹出），汇总类保持普通，避免半夜吵人。 */
    private int priority(String event) {
        return switch (PushHttp.text(event)) {
            case "TASK_REMINDER", "CHECKLIST_REMINDER" -> 4;
            default -> 3;
        };
    }

    /** tags 里的短词会被 ntfy 渲染成图标，用内置的 bell / memo 即可，不依赖客户端主题。 */
    private String tag(String event) {
        return switch (PushHttp.text(event)) {
            case "TASK_REMINDER", "CHECKLIST_REMINDER" -> "bell";
            case "HABIT_DIGEST" -> "memo";
            default -> "white_check_mark";
        };
    }

    private String detail(PushModels.Message message) {
        StringBuilder text = new StringBuilder();
        String kicker = PushHttp.text(message.kicker());
        if (!kicker.isEmpty()) text.append(kicker).append('\n');
        text.append(PushHttp.text(message.body()));
        for (PushModels.Fact fact : message.facts()) {
            if (text.length() > 0) text.append('\n');
            text.append(fact.label()).append(' ').append(PushText.oneLine(fact.value(), 40));
        }
        for (String line : message.lines()) {
            if (text.length() > 0) text.append('\n');
            text.append("• ").append(PushHttp.text(line));
        }
        return PushHttp.truncate(text.toString(), 4000);
    }
}
