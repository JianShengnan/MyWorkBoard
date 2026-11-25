package com.salarytracker.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把一条结构化消息渲染成飞书「消息卡片」。
 *
 * <p>自定义机器人（Webhook）只支持发消息、不支持按钮回调，所以卡片里的按钮都是 url 跳转；
 * 想让按钮直接完成任务，需要换成企业自建应用 + 卡片回调，届时只要把 actions 换成回传 value 的按钮即可。
 * 现在至少把「什么任务、哪个清单、几点截止、还剩多少、检查项进度、描述」这些在卡片里说清楚，
 * 不用点进网页才知道。</p>
 */
final class FeishuCard {
    private static final int MAX_FIELDS = 8;

    private FeishuCard() {
    }

    static ObjectNode build(ObjectMapper mapper, PushModels.Message message, String event, String footer) {
        ObjectNode card = mapper.createObjectNode();
        ObjectNode config = card.putObject("config");
        config.put("wide_screen_mode", true);
        config.put("enable_forward", true);

        ObjectNode header = card.putObject("header");
        header.put("template", PushText.template(event));
        header.putObject("title").put("tag", "plain_text")
                .put("content", PushText.oneLine(PushHttp.text(message.title()), 120));

        ArrayNode elements = card.putArray("elements");

        String kicker = PushHttp.text(message.kicker());
        if (!kicker.isEmpty()) {
            ObjectNode line = elements.addObject();
            line.put("tag", "div");
            line.putObject("text").put("tag", "lark_md")
                    .put("content", "<font color='grey'>" + PushText.escape(kicker) + "</font>");
        }

        List<PushModels.Fact> facts = message.facts();
        if (!facts.isEmpty()) {
            ObjectNode block = elements.addObject();
            block.put("tag", "div");
            ArrayNode fields = block.putArray("fields");
            for (PushModels.Fact fact : facts.subList(0, Math.min(MAX_FIELDS, facts.size()))) {
                String label = PushText.escape(PushHttp.text(fact.label()));
                String value = PushText.escape(PushText.oneLine(fact.value(), 60));
                if (label.isEmpty() || value.isEmpty()) continue;
                ObjectNode field = fields.addObject();
                field.put("is_short", true);
                field.putObject("text").put("tag", "lark_md").put("content", "**" + label + "**\n" + value);
            }
        }

        String body = PushHttp.text(message.body());
        if (!body.isEmpty() && message.facts().isEmpty()) {
            ObjectNode block = elements.addObject();
            block.put("tag", "div");
            block.putObject("text").put("tag", "lark_md").put("content", PushText.escape(body));
        }

        if (!message.lines().isEmpty()) {
            StringBuilder content = new StringBuilder();
            for (String entry : message.lines()) {
                String text = PushHttp.text(entry);
                if (text.isEmpty()) continue;
                if (!content.isEmpty()) content.append('\n');
                content.append("• ").append(PushText.escape(text));
            }
            if (!content.isEmpty()) {
                ObjectNode block = elements.addObject();
                block.put("tag", "div");
                block.putObject("text").put("tag", "lark_md").put("content", content.toString());
            }
        }

        String link = PushHttp.text(message.link());
        if (!link.isEmpty() || !message.actions().isEmpty()) {
            elements.addObject().put("tag", "hr");
            ObjectNode action = elements.addObject();
            action.put("tag", "action");
            ArrayNode buttons = action.putArray("actions");
            if (!link.isEmpty()) {
                String label = PushHttp.text(message.linkLabel());
                buttons.addObject()
                        .put("tag", "button")
                        .put("type", "primary")
                        .put("url", link)
                        .putObject("text").put("tag", "plain_text")
                        .put("content", label.isEmpty() ? "在 WorkBoard 打开" : label);
            }
            for (PushModels.Action extra : message.actions()) {
                String url = PushHttp.text(extra.url());
                if (url.isEmpty()) continue;
                buttons.addObject()
                        .put("tag", "button")
                        .put("type", "default")
                        .put("url", url)
                        .putObject("text").put("tag", "plain_text")
                        .put("content", PushText.oneLine(extra.text(), 20));
            }
            // 没有链接时按钮区只剩空白，去掉这一段
            if (buttons.isEmpty()) {
                elements.remove(elements.size() - 1);
                elements.remove(elements.size() - 1);
            }
        }

        String note = PushHttp.text(footer);
        if (!note.isEmpty()) {
            ObjectNode block = elements.addObject();
            block.put("tag", "note");
            block.putArray("elements").addObject().put("tag", "plain_text").put("content", note);
        }
        return card;
    }
}
