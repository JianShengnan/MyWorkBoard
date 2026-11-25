package com.salarytracker.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 卡片结构上的硬约束：PATCH 更新要求 update_multi=true，流式帧与正式回答必须同构。 */
class FeishuCardsTest {
    private final FeishuCards cards = new FeishuCards(new ObjectMapper());

    @Test
    void everyCardDeclaresUpdateMulti() {
        assertTrue(cards.answer("问题", "回答", "http://x/agent").path("config").path("update_multi").asBoolean());
        assertTrue(cards.streaming("部分内容", "http://x/agent").path("config").path("update_multi").asBoolean());
        assertTrue(cards.notice("标题", "正文", "blue").path("config").path("update_multi").asBoolean());
    }

    @Test
    void streamingShowsPlaceholderBeforeFirstToken() {
        JsonNode card = cards.streaming("", "http://x/agent");
        assertTrue(card.toString().contains("正在思考"));
    }

    @Test
    void streamingAndAnswerShareLayout() {
        JsonNode streaming = cards.streaming("半句话", "http://x/agent");
        JsonNode answer = cards.answer("问题", "完整回答", "http://x/agent");
        // 元素数量一致，替换最终卡片时布局不会跳动
        assertEquals(answer.path("elements").size(), streaming.path("elements").size());
        assertEquals(answer.path("header").path("title").path("content").asText(),
                streaming.path("header").path("title").path("content").asText());
    }

    @Test
    void longContentIsTrimmedWithHint() {
        String huge = "x".repeat(10_000);
        assertTrue(cards.streaming(huge, "http://x/agent").toString().contains("完整回复请在网页里查看"));
    }
}
