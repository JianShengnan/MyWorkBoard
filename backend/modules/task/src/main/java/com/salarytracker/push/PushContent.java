package com.salarytracker.push;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 把业务对象组装成待推送内容。
 *
 * <p>排版口径只在这里定义一次：飞书卡片的两列字段、Bark 的副标题、邮件的表格都消费同一份
 * {@link PushModels.Message}，各通道不再自己拼字符串。</p>
 */
public final class PushContent {
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日");

    private PushContent() {
    }

    /** 任务提醒 / 检查项提醒共用的任务快照 */
    public record TaskNotice(String title, String listName, String priority, Instant dueAt, boolean allDay,
                             String timezone, String description, int checklistTotal, int checklistDone,
                             List<String> pendingItems, List<String> tags, boolean daily, Instant remindAt) {
        public TaskNotice {
            pendingItems = pendingItems == null ? List.of() : List.copyOf(pendingItems);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public TaskNotice withDaily(boolean value) {
            return new TaskNotice(title, listName, priority, dueAt, allDay, timezone, description, checklistTotal,
                    checklistDone, pendingItems, tags, value, remindAt);
        }
    }

    public static PushModels.Message task(TaskNotice notice, String taskPublicId) {
        ZoneId zone = PushText.zone(notice.timezone());
        List<PushModels.Fact> facts = new ArrayList<>();
        facts.add(new PushModels.Fact("截止", notice.dueAt() == null
                ? "未设置" : PushText.due(notice.dueAt(), zone, notice.allDay())));
        if (!PushHttp.text(notice.listName()).isEmpty()) {
            facts.add(new PushModels.Fact("清单", notice.listName()));
        }
        if (!PushText.priority(notice.priority()).isEmpty()) {
            facts.add(new PushModels.Fact("优先级", PushText.priority(notice.priority())));
        }
        if (notice.checklistTotal() > 0) {
            facts.add(new PushModels.Fact("检查项", notice.checklistDone() + " / " + notice.checklistTotal() + " 已完成"));
        }
        if (!notice.tags().isEmpty()) {
            facts.add(new PushModels.Fact("标签", String.join(" / ", notice.tags())));
        }
        facts.add(new PushModels.Fact("提醒", notice.daily() ? "每天一次，直到完成" : "单次"));

        List<String> lines = new ArrayList<>();
        for (String item : notice.pendingItems()) {
            lines.add(item);
        }

        String kicker = "任务提醒" + (notice.listName() == null || notice.listName().isBlank()
                ? "" : " · " + notice.listName()) + (notice.daily() ? " · 每日重复" : "");
        return new PushModels.Message(notice.title(), kicker, excerpt(notice.description()),
                "/tasks/all?task=" + taskPublicId, "打开任务", facts, lines,
                List.of(new PushModels.Action("全部提醒", "/tasks/inbox-notify")));
    }

    public static PushModels.Message checklist(TaskNotice notice, String itemTitle, String taskPublicId) {
        ZoneId zone = PushText.zone(notice.timezone());
        List<PushModels.Fact> facts = new ArrayList<>();
        facts.add(new PushModels.Fact("所属任务", notice.title()));
        if (notice.dueAt() != null) {
            facts.add(new PushModels.Fact("任务截止", PushText.due(notice.dueAt(), zone, notice.allDay())));
        }
        if (notice.checklistTotal() > 0) {
            facts.add(new PushModels.Fact("检查项", notice.checklistDone() + " / " + notice.checklistTotal() + " 已完成"));
        }
        if (!PushText.priority(notice.priority()).isEmpty()) {
            facts.add(new PushModels.Fact("优先级", PushText.priority(notice.priority())));
        }
        if (!PushHttp.text(notice.listName()).isEmpty()) {
            facts.add(new PushModels.Fact("清单", notice.listName()));
        }

        String kicker = "检查项提醒" + (notice.listName() == null || notice.listName().isBlank()
                ? "" : " · " + notice.listName());
        return new PushModels.Message(itemTitle, kicker, excerpt(notice.description()),
                "/tasks/all?task=" + taskPublicId, "打开任务", facts, List.of(),
                List.of(new PushModels.Action("全部提醒", "/tasks/inbox-notify")));
    }

    /**
     * 习惯汇总：标题给出总数，正文逐条列出没打卡的习惯并带上连续天数，
     * 不用点进网页就知道今天差什么。
     */
    public static PushModels.Message habitDigest(int missing, int finished, LocalDate day, ZoneId zone,
                                                 List<PushModels.Fact> facts, List<String> lines) {
        List<PushModels.Fact> merged = new ArrayList<>();
        merged.add(new PushModels.Fact("已完成", finished + " 个"));
        merged.add(new PushModels.Fact("待打卡", missing + " 个"));
        if (facts != null) merged.addAll(facts);
        String kicker = "习惯汇总 · " + day.format(MONTH_DAY);
        return new PushModels.Message("今天还有 " + missing + " 个习惯没打卡", kicker,
                "睡前收个尾，明天不用补。", "/tasks/habits", "去打卡", merged, lines,
                List.of(new PushModels.Action("全部提醒", "/tasks/inbox-notify")));
    }

    /** 提醒时间本身也值得展示（尤其是「提前 30 分钟」这种相对提醒） */
    public static String remindClock(Instant remindAt, ZoneId zone) {
        if (remindAt == null) return "";
        return LocalDateTime.ofInstant(remindAt, zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"));
    }

    private static String excerpt(String description) {
        String text = PushHttp.text(description);
        if (text.isEmpty()) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return PushHttp.truncate(flat, 160);
    }
}
