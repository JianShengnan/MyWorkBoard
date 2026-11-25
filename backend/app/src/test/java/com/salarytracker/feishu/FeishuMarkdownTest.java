package com.salarytracker.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** lark_md 不支持表格/标题/分隔线，渲染前必须改写掉，否则飞书里会看到一堆竖线和井号。 */
class FeishuMarkdownTest {
    @Test
    void convertsTableToBulletLines() {
        String input = """
                今天到期的任务有 2 条：

                | 任务 | 优先级 | 说明 |
                |---|---|---|
                | 单词 | 高 | 每日重复（第 18 次） |
                | 每日提醒 | 高 | 每日重复，含 7 个检查项 |
                """;
        String output = FeishuMarkdown.render(input);
        assertFalse(output.contains("|"), "渲染后不应残留表格竖线");
        assertTrue(output.contains("- **单词** · 优先级：高 · 说明：每日重复（第 18 次）"), output);
        assertTrue(output.contains("- **每日提醒**"), output);
    }

    @Test
    void keepsTableReadableWhenCellsContainEscapedPipe() {
        String input = """
                | 名称 | 备注 |
                |---|:---:|
                | A\\|B | 含竖线 |
                """;
        String output = FeishuMarkdown.render(input);
        assertTrue(output.contains("**A|B**"), output);
        assertTrue(output.contains("备注：含竖线"), output);
    }

    @Test
    void turnsHeadingsIntoBoldAndDropsRules() {
        String input = "### 今日概览\n---\n正文一行\n\n***\n结束";
        String output = FeishuMarkdown.render(input);
        assertEquals("**今日概览**\n正文一行\n\n结束", output);
    }

    @Test
    void leavesOrdinaryMarkdownUntouched() {
        String input = "- 第一项\n- **第二项**\n\n普通段落 `code`";
        assertEquals(input, FeishuMarkdown.render(input));
    }

    @Test
    void handlesEmptyAndBlankInput() {
        assertEquals("", FeishuMarkdown.render(null));
        assertEquals("", FeishuMarkdown.render(""));
    }
}
