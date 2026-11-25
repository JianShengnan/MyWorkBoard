package com.salarytracker.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 飞书卡片拼装。
 *
 * <p>用卡片而不是纯文本，是因为卡片能加标题栏、分隔线和按钮；AI 回复里的 markdown
 * （列表、加粗、代码块）也能原样渲染，比纯文本消息可读得多。</p>
 */
@Component
public class FeishuCards {
    /** 单条消息有 30KB 上限，这里给正文留足余量，超长直接截断并提示回网页看完整版 */
    private static final int MAX_MARKDOWN = 3_400;

    private final ObjectMapper mapper;

    public FeishuCards(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** AI 回答：标题栏 + markdown 正文 + 回网页继续的按钮 */
    public ObjectNode answer(String question, String markdown, String link) {
        ObjectNode card = base("WorkBoard 助手", "blue");
        ArrayNode elements = (ArrayNode) card.get("elements");
        elements.add(markdown(trim(markdown)));
        elements.add(hr());
        elements.add(buttons(action("在网页继续这个会话", link, "default")));
        return card;
    }

    /** 一句提示（绑定成功、帮助、错误） */
    public ObjectNode notice(String title, String body, String template) {
        ObjectNode card = base(title, template);
        ArrayNode elements = (ArrayNode) card.get("elements");
        elements.add(markdown(trim(body)));
        return card;
    }

    /** 带按钮的提示 */
    public ObjectNode noticeWithAction(String title, String body, String template, String label, String url) {
        ObjectNode card = notice(title, body, template);
        ArrayNode elements = (ArrayNode) card.get("elements");
        elements.add(hr());
        elements.add(buttons(action(label, url, "primary")));
        return card;
    }

    /**
     * 可回传的按钮。自建应用的卡片能带 value，点下去走 /api/v1/feishu/card 回调，
     * 不用跳浏览器——这是相比自定义机器人 webhook 最关键的区别。
     */
    public ObjectNode callbackAction(String label, String action, String payload, String type) {
        ObjectNode button = mapper.createObjectNode();
        button.put("tag", "button");
        ObjectNode text = button.putObject("text");
        text.put("tag", "plain_text");
        text.put("content", label);
        button.put("type", type);
        ObjectNode value = button.putObject("value");
        value.put("action", action);
        value.put("payload", payload == null ? "" : payload);
        return button;
    }

    private ObjectNode base(String title, String template) {
        ObjectNode card = mapper.createObjectNode();
        ObjectNode config = card.putObject("config");
        config.put("wide_screen_mode", true);
        ObjectNode header = card.putObject("header");
        header.put("template", template);
        ObjectNode headerTitle = header.putObject("title");
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", title);
        card.putArray("elements");
        return card;
    }

    private ObjectNode markdown(String content) {
        ObjectNode element = mapper.createObjectNode();
        element.put("tag", "div");
        ObjectNode text = element.putObject("text");
        text.put("tag", "lark_md");
        text.put("content", content == null || content.isBlank() ? "（空）" : content);
        return element;
    }

    private ObjectNode hr() {
        ObjectNode element = mapper.createObjectNode();
        element.put("tag", "hr");
        return element;
    }

    private ObjectNode buttons(ObjectNode... items) {
        ObjectNode action = mapper.createObjectNode();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        for (ObjectNode item : items) actions.add(item);
        return action;
    }

    private ObjectNode action(String label, String url, String type) {
        ObjectNode button = mapper.createObjectNode();
        button.put("tag", "button");
        ObjectNode text = button.putObject("text");
        text.put("tag", "plain_text");
        text.put("content", label);
        button.put("type", type);
        ObjectNode multi = button.putObject("multi_url");
        multi.put("url", url == null || url.isBlank() ? "http://jsn1024.cn" : url);
        return button;
    }

    private String trim(String markdown) {
        if (markdown == null) return "";
        String value = markdown.trim();
        if (value.length() <= MAX_MARKDOWN) return value;
        return value.substring(0, MAX_MARKDOWN) + "\n\n…内容较长，完整回复请在网页里查看。";
    }
}
