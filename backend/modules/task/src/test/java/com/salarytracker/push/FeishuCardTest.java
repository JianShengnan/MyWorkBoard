package com.salarytracker.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 飞书卡片的结构：标题配色、字段、正文、按钮、转义。卡片拼错飞书会直接拒收，这里先把形状锁住。 */
class FeishuCardTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rendersTitleTemplateAndFacts() {
        PushModels.Message message = new PushModels.Message("提交周报", "任务提醒 · 工作", "把数据补进模板",
                "https://example.com/tasks/all?task=1", "打开任务",
                List.of(new PushModels.Fact("截止", "今天 18:00"), new PushModels.Fact("优先级", "高")),
                List.of("待办：补数据"), List.of(new PushModels.Action("全部提醒", "/tasks/inbox-notify")));
        JsonNode card = FeishuCard.build(mapper, message, "TASK_REMINDER", "WorkBoard · 11-25 14:00");

        assertEquals("blue", card.path("header").path("template").asText());
        assertEquals("提交周报", card.path("header").path("title").path("content").asText());

        JsonNode elements = card.path("elements");
        assertTrue(elements.toString().contains("**截止**"), "字段应渲染成加粗标签");
        assertTrue(elements.toString().contains("今天 18:00"));
        assertTrue(elements.toString().contains("• 待办：补数据"), "自由行渲染成项目符号");
        assertTrue(elements.toString().contains("WorkBoard · 11-25 14:00"), "页脚带发送时间");

        JsonNode buttons = null;
        for (JsonNode element : elements) {
            if ("action".equals(element.path("tag").asText())) buttons = element.path("actions");
        }
        assertEquals(2, buttons.size());
        assertEquals("primary", buttons.get(0).path("type").asText());
        assertEquals("https://example.com/tasks/all?task=1", buttons.get(0).path("url").asText());
        assertEquals("全部提醒", buttons.get(1).path("text").path("content").asText());
    }

    @Test
    void escapesMarkupAndCollapsesNewlinesInFields() {
        PushModels.Message message = new PushModels.Message("A & B", null, null, "", null,
                List.of(new PushModels.Fact("清单", "<script>\n坏东西")), List.of(), List.of());
        String json = FeishuCard.build(mapper, message, "TEST", "").toString();

        assertTrue(json.contains("A & B"), "标题走 plain_text，不需要也不应该转义");
        assertTrue(json.contains("&lt;script&gt;"), "尖括号必须转义，否则 lark_md 当标签解析");
        assertTrue(json.contains("坏东西"));
        assertFalse(json.contains("<script>"));
        assertFalse(json.contains("\\n坏东西"), "字段里的换行要压成空格，否则卡片破版");
    }

    @Test
    void skipsActionBlockWhenNothingToOpen() {
        PushModels.Message message = new PushModels.Message("只有标题", null, null, "", null,
                List.of(), List.of(), List.of());
        JsonNode elements = FeishuCard.build(mapper, message, "HABIT_DIGEST", "").path("elements");

        assertEquals(0, elements.size(), "没有链接也没有额外按钮时不该留空的动作区");
    }

    @Test
    void eventDecidesTemplateAndLabel() {
        assertEquals("turquoise", PushText.template("HABIT_DIGEST"));
        assertEquals("orange", PushText.template("CHECKLIST_REMINDER"));
        assertEquals("green", PushText.template("TEST"));
        assertEquals("检查项提醒", PushText.eventLabel("CHECKLIST_REMINDER"));
    }

    @Test
    void dueTextIsHumanReadable() {
        java.time.Instant soon = java.time.Instant.now().plusSeconds(25 * 60);
        String text = PushText.due(soon, java.time.ZoneId.of("Asia/Shanghai"), false);
        assertTrue(text.startsWith("今天 "), text);
        assertTrue(text.contains("还有 25 分钟"), text);

        java.time.Instant late = java.time.Instant.now().minusSeconds(90 * 60);
        String overdue = PushText.due(late, java.time.ZoneId.of("Asia/Shanghai"), false);
        assertTrue(overdue.contains("已逾期 1 小时 30 分"), overdue);

        assertEquals("高", PushText.priority("HIGH"));
    }
}
