package com.salarytracker.push;

import java.util.List;
import java.util.Map;

/** 站外推送的数据形状：通道配置、偏好、测试结果、投递记录。 */
public final class PushModels {
    private PushModels() {
    }

    /** ready=false 表示必填项缺失（或服务端 SMTP 未配置），前端据此提示「还没配好」 */
    public record Channel(long id, String kind, boolean enabled, boolean ready, Map<String, String> config,
                          String lastTestAt, Boolean lastTestOk, String lastError, String lastSentAt, long revision) {
    }

    public record ChannelCommand(Boolean enabled, Map<String, String> config, Long revision) {
    }

    public record Preference(boolean taskReminder, boolean checklistReminder, boolean habitDigest,
                             int habitDigestHour, String quietFrom, String quietTo, long revision) {
    }

    public record PreferenceCommand(Boolean taskReminder, Boolean checklistReminder, Boolean habitDigest,
                                    Integer habitDigestHour, String quietFrom, String quietTo, Long revision) {
    }

    public record TestResult(boolean ok, String detail) {
    }

    public record Deleted(boolean deleted) {
    }

    public record Delivery(String kind, String event, String status, String title, String detail, String createdAt) {
    }

    /** 键值对，飞书卡片里渲染成两列短字段（截止 / 清单 / 优先级 …） */
    public record Fact(String label, String value) {
    }

    /** 额外按钮。自定义机器人不支持回调，所以按钮都是跳转链接 */
    public record Action(String text, String url) {
    }

    /**
     * 一条待推送的消息。飞书渲染成卡片、Bark 渲染成副标题 + 正文、邮件渲染成 HTML，
     * 三个通道共用同一份结构化内容，避免各自拼字符串。
     *
     * @param kicker 标题上方的小字（任务提醒 / 检查项提醒 / 习惯汇总）
     * @param facts  两列字段
     * @param lines  自由文本行，每行渲染成一条项目符号
     */
    public record Message(String title, String kicker, String body, String link, String linkLabel,
                          List<Fact> facts, List<String> lines, List<Action> actions) {
        public Message {
            facts = facts == null ? List.of() : List.copyOf(facts);
            lines = lines == null ? List.of() : List.copyOf(lines);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        /** 老调用点的简写：只有标题、正文、深链 */
        public Message(String title, String body, String link) {
            this(title, null, body, link, null, List.of(), List.of(), List.of());
        }
    }
}
