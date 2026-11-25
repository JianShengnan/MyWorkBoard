package com.salarytracker.push;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 推送文案的小工具：相对时间、优先级标签、颜色映射、转义。 */
final class PushText {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日");

    private PushText() {
    }

    static ZoneId zone(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        } catch (Exception exception) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /**
     * 「今天 18:00（还有 12 分钟）」「明天 09:00」「11月28日 09:00」「已逾期 2 小时」。
     * 卡片里最有用的信息之一，比 ISO 时间戳可读得多。
     */
    static String due(Instant due, ZoneId zone, boolean allDay) {
        if (due == null) return "";
        LocalDateTime local = LocalDateTime.ofInstant(due, zone);
        LocalDate day = local.toLocalDate();
        LocalDate today = LocalDate.now(zone);
        String dayLabel;
        if (day.equals(today)) dayLabel = "今天";
        else if (day.equals(today.plusDays(1))) dayLabel = "明天";
        else if (day.equals(today.minusDays(1))) dayLabel = "昨天";
        else dayLabel = local.format(MONTH_DAY);
        String clock = allDay ? "" : " " + local.format(TIME);

        String relative = "";
        Duration gap = Duration.between(Instant.now(), due);
        // 四舍五入到分钟：还剩 24 分 59 秒说「还有 25 分钟」比「24 分钟」更贴近直觉
        long minutes = Math.round(gap.getSeconds() / 60.0);
        if (minutes < -1) {
            relative = "已逾期 " + span(-minutes);
        } else if (minutes <= 90) {
            relative = "还有 " + Math.max(1, minutes) + " 分钟";
        } else if (minutes <= 60 * 8) {
            relative = "还有 " + Math.round(minutes / 60.0) + " 小时";
        }
        return (dayLabel + clock + (relative.isEmpty() ? "" : "（" + relative + "）")).trim();
    }

    /** 时长的人话版本：90 分钟 → 1 小时 30 分 */
    static String span(long minutes) {
        if (minutes < 60) return minutes + " 分钟";
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours < 24) return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
        long days = hours / 24;
        long hourRest = hours % 24;
        return hourRest == 0 ? days + " 天" : days + " 天 " + hourRest + " 小时";
    }

    static String priority(String value) {
        if (value == null) return "";
        return switch (value.toUpperCase()) {
            case "HIGH", "URGENT" -> "高";
            case "MEDIUM", "NORMAL" -> "中";
            case "LOW" -> "低";
            default -> "";
        };
    }

    /** 飞书卡片头部的颜色模板 */
    static String template(String event) {
        return switch (event == null ? "" : event) {
            case "TASK_REMINDER" -> "blue";
            case "CHECKLIST_REMINDER" -> "orange";
            case "HABIT_DIGEST" -> "turquoise";
            case "TEST" -> "green";
            default -> "grey";
        };
    }

    static String eventLabel(String event) {
        return switch (event == null ? "" : event) {
            case "TASK_REMINDER" -> "任务提醒";
            case "CHECKLIST_REMINDER" -> "检查项提醒";
            case "HABIT_DIGEST" -> "习惯汇总";
            case "TEST" -> "通道测试";
            default -> "提醒";
        };
    }

    /** lark_md 会把 &lt; &gt; &amp; 当标签解析，用户正文里的尖括号必须转义 */
    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 卡片字段是单行排版，换行会破版，压成空格 */
    static String oneLine(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

}
