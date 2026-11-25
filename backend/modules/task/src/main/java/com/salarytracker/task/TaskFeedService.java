package com.salarytracker.task;

import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.platform.NotFoundException;
import com.salarytracker.task.TaskModels.FeedToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskFeedService {
    private static final DateTimeFormatter ICS_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final JdbcTemplate jdbc;
    private final CurrentUserResolver currentUser;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Window> limits = new ConcurrentHashMap<>();

    public TaskFeedService(JdbcTemplate jdbc, CurrentUserResolver currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /**
     * 订阅状态。地址原文会一起返回：只存哈希时，用户刷新页面就再也看不到地址，
     * 想复制只能重新生成，旧地址立即失效——这个代价比「数据库里多一个只读令牌」更大。
     */
    public FeedToken status(String baseUrl) {
        List<FeedToken> rows = jdbc.query("SELECT token_hint,token_value,created_at,last_accessed_at " +
                        "FROM task_feed_token WHERE user_id=? AND revoked_at IS NULL ORDER BY id DESC LIMIT 1",
                (result, row) -> new FeedToken(true, result.getString(1), url(baseUrl, result.getString(2)),
                        instant(result.getTimestamp(3)), instant(result.getTimestamp(4))), currentUser.id());
        return rows.isEmpty() ? new FeedToken(false, null, null, null, null) : rows.get(0);
    }

    @Transactional
    public FeedToken create(String baseUrl) {
        if (status(baseUrl).active()) throw new IllegalStateException("已有有效订阅地址，请使用轮换");
        return issue(baseUrl);
    }

    @Transactional
    public FeedToken rotate(String baseUrl) {
        revoke();
        return issue(baseUrl);
    }

    @Transactional
    public FeedToken revoke() {
        jdbc.update("UPDATE task_feed_token SET revoked_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL",
                currentUser.id());
        return new FeedToken(false, null, null, null, null);
    }

    @Transactional
    public String feed(String rawToken, String remoteAddress) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) throw new NotFoundException("订阅不存在");
        String tokenHash = hash(rawToken);
        limit(remoteAddress, tokenHash);
        List<Owner> owners = jdbc.query("SELECT f.id,f.user_id,s.feed_include_description,s.timezone " +
                        "FROM task_feed_token f JOIN task_setting s ON s.user_id=f.user_id " +
                        "WHERE f.token_hash=? AND f.revoked_at IS NULL",
                (result, row) -> new Owner(result.getLong(1), result.getLong(2), result.getBoolean(3),
                        result.getString(4)), tokenHash);
        if (owners.isEmpty()) throw new NotFoundException("订阅不存在");
        Owner owner = owners.get(0);
        ZoneId zone = zone(owner.timezone());
        jdbc.update("UPDATE task_feed_token SET last_accessed_at=CURRENT_TIMESTAMP(3) WHERE id=?", owner.id());
        List<FeedTask> tasks = jdbc.query("SELECT t.id,t.public_id,t.title,t.description,t.due_at,t.start_at," +
                        "t.all_day,t.priority,l.name," +
                        "(SELECT COUNT(*) FROM task_checklist_item c WHERE c.task_id=t.id AND c.deleted=FALSE)," +
                        "(SELECT COUNT(*) FROM task_checklist_item c WHERE c.task_id=t.id AND c.deleted=FALSE " +
                        " AND c.completed=TRUE) " +
                        "FROM task t JOIN task_list l ON l.id=t.list_id " +
                        "WHERE t.user_id=? AND t.deleted=FALSE AND t.status='OPEN' AND t.due_at IS NOT NULL " +
                        "ORDER BY t.due_at,t.id",
                (result, row) -> new FeedTask(result.getLong(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getTimestamp(5).toInstant(),
                        result.getTimestamp(6) == null ? null : result.getTimestamp(6).toInstant(),
                        result.getBoolean(7), result.getString(8), result.getString(9),
                        result.getInt(10), result.getInt(11)), owner.userId());
        StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//MyWorkBoard//Tasks//CN\r\nCALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\nX-WR-CALNAME:")
                .append(escape("WorkBoard 日历")).append("\r\n");
        for (FeedTask task : tasks) {
            out.append("BEGIN:VEVENT\r\nUID:").append(task.publicId()).append("@myworkboard\r\n")
                    .append("DTSTAMP:").append(ICS_TIME.format(Instant.now())).append("\r\n");
            if (task.allDay()) {
                // 全天任务：DTEND 是「不含」的次日，写错会导致日历里多出一天
                LocalDate day = task.dueAt().atZone(zone).toLocalDate();
                out.append("DTSTART;VALUE=DATE:").append(ICS_DATE.format(day)).append("\r\n")
                        .append("DTEND;VALUE=DATE:").append(ICS_DATE.format(day.plusDays(1))).append("\r\n");
            } else if (task.startAt() != null && task.startAt().isBefore(task.dueAt())) {
                out.append("DTSTART:").append(ICS_TIME.format(task.startAt())).append("\r\n")
                        .append("DTEND:").append(ICS_TIME.format(task.dueAt())).append("\r\n");
            } else {
                // 只有截止时间：按「时间点」事件写，带上 DTEND 会在部分客户端拉出一个假时段
                out.append("DTSTART:").append(ICS_TIME.format(task.dueAt())).append("\r\n");
            }
            out.append("SUMMARY:").append(escape(task.title())).append("\r\n")
                    .append("CATEGORIES:").append(escape(task.listName())).append("\r\n")
                    .append("TRANSP:TRANSPARENT\r\n")
                    .append("STATUS:CONFIRMED\r\n");
            String description = description(task, owner.includeDescription());
            if (!description.isEmpty()) out.append("DESCRIPTION:").append(escape(description)).append("\r\n");
            List<Integer> reminders = jdbc.query("SELECT offset_minutes FROM task_reminder WHERE task_id=? AND kind='RELATIVE' " +
                            "AND cancelled_at IS NULL AND offset_minutes IS NOT NULL ORDER BY offset_minutes",
                    (result, row) -> result.getInt(1), task.id());
            for (Integer minutes : reminders) out.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:")
                    .append(escape(task.title())).append("\r\nTRIGGER:-PT").append(minutes).append("M\r\nEND:VALARM\r\n");
            out.append("END:VEVENT\r\n");
        }
        appendCountdowns(out, owner.userId(), zone);
        appendHabits(out, owner.userId(), zone);
        appendWorkRecords(out, owner.userId(), zone);
        appendLedger(out, owner.userId(), zone);
        return out.append("END:VCALENDAR\r\n").toString();
    }

    /** 事件详情：清单、优先级、检查项进度，任务描述按用户设置决定是否带出。 */
    private String description(FeedTask task, boolean includeDescription) {
        StringBuilder description = new StringBuilder();
        description.append("清单：").append(task.listName());
        String priority = priorityLabel(task.priority());
        if (!priority.isEmpty()) description.append("\n优先级：").append(priority);
        if (task.checklistTotal() > 0) {
            description.append("\n检查项：").append(task.checklistDone()).append('/').append(task.checklistTotal())
                    .append(" 已完成");
        }
        if (includeDescription && task.description() != null && !task.description().isBlank()) {
            description.append("\n\n").append(task.description().trim());
        }
        return description.toString();
    }

    /** 倒数纪念日：没有「完成」概念，按目标日展开；`repeat_rule` 决定是否重复。 */
    private void appendCountdowns(StringBuilder out, long userId, ZoneId zone) {
        List<CountdownEvent> rows = jdbc.query("SELECT c.public_id,c.title,c.target_date,c.repeat_rule," +
                        "c.repeat_yearly,c.precise_time,c.note,COALESCE(g.name,'') " +
                        "FROM countdown c LEFT JOIN countdown_group g ON g.public_id=c.group_id AND g.deleted_at IS NULL " +
                        "WHERE c.user_id=? AND c.deleted_at IS NULL AND c.archived=0 ORDER BY c.target_date,c.id",
                (result, row) -> new CountdownEvent(result.getString(1), result.getString(2),
                        result.getDate(3).toLocalDate(), result.getString(4), result.getBoolean(5),
                        result.getString(6), result.getString(7), result.getString(8)), userId);
        String stamp = ICS_TIME.format(Instant.now());
        for (CountdownEvent item : rows) {
            out.append("BEGIN:VEVENT\r\nUID:").append(item.publicId()).append("@countdown\r\n")
                    .append("DTSTAMP:").append(stamp).append("\r\n");
            LocalTime time = parseTime(item.preciseTime());
            if (time == null) {
                out.append("DTSTART;VALUE=DATE:").append(ICS_DATE.format(item.day())).append("\r\n")
                        .append("DTEND;VALUE=DATE:").append(ICS_DATE.format(item.day().plusDays(1))).append("\r\n");
            } else {
                // 精确到时刻的倒数日只写起点，写 DTEND 会在客户端拉出一段假时长
                out.append("DTSTART:").append(ICS_TIME.format(item.day().atTime(time).atZone(zone).toInstant()))
                        .append("\r\n");
            }
            String rule = countdownRule(item.repeatRule(), item.repeatYearly());
            if (!rule.isEmpty()) out.append("RRULE:").append(rule).append("\r\n");
            out.append("SUMMARY:").append(escape(item.title())).append("\r\n");
            if (!item.group().isEmpty()) out.append("CATEGORIES:").append(escape(item.group())).append("\r\n");
            StringBuilder description = new StringBuilder();
            if (!item.group().isEmpty()) description.append("分组：").append(item.group());
            if (item.note() != null && !item.note().isBlank()) {
                if (description.length() > 0) description.append('\n');
                description.append(item.note().trim());
            }
            if (description.length() > 0) {
                out.append("DESCRIPTION:").append(escape(description.toString())).append("\r\n");
            }
            out.append("TRANSP:TRANSPARENT\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\n");
        }
    }

    /**
     * 习惯：只导出有固定节律的（每日 / 自定义星期 / 每 N 天）。
     * WEEKLY_N、MONTHLY_N 是「周期目标制」，没有具体日期，写进日历只会误导。
     */
    private void appendHabits(StringBuilder out, long userId, ZoneId zone) {
        List<HabitEvent> rows = jdbc.query("SELECT public_id,name,frequency,custom_days,remind_at,start_date," +
                        "interval_days,target_count FROM habit " +
                        "WHERE user_id=? AND deleted_at IS NULL AND is_archived=FALSE ORDER BY sort_order,id",
                (result, row) -> new HabitEvent(result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getDate(6).toLocalDate(),
                        result.getInt(7), result.getInt(8)), userId);
        String stamp = ICS_TIME.format(Instant.now());
        for (HabitEvent item : rows) {
            String rule = habitRule(item.frequency(), item.customDays(), item.intervalDays());
            if (rule.isEmpty()) continue;
            LocalTime time = parseTime(item.remindAt());
            out.append("BEGIN:VEVENT\r\nUID:").append(item.publicId()).append("@habit\r\n")
                    .append("DTSTAMP:").append(stamp).append("\r\n");
            if (time == null) {
                out.append("DTSTART;VALUE=DATE:").append(ICS_DATE.format(item.startDate())).append("\r\n")
                        .append("DTEND;VALUE=DATE:").append(ICS_DATE.format(item.startDate().plusDays(1))).append("\r\n");
            } else {
                out.append("DTSTART:").append(ICS_TIME.format(item.startDate().atTime(time).atZone(zone).toInstant()))
                        .append("\r\n");
            }
            out.append("RRULE:").append(rule).append("\r\n")
                    .append("SUMMARY:").append(escape(item.name())).append("\r\n")
                    .append("CATEGORIES:").append(escape("习惯")).append("\r\n")
                    .append("DESCRIPTION:").append(escape(habitDescription(item))).append("\r\n")
                    // 打卡是提醒性质，不该把整段时间标记成忙碌
                    .append("TRANSP:TRANSPARENT\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\n");
        }
    }

    /** 工时：只取最近 90 天，避免把历史考勤全部灌进日历。 */
    private void appendWorkRecords(StringBuilder out, long userId, ZoneId zone) {
        LocalDate since = LocalDate.now(zone).minusDays(90);
        List<WorkEvent> rows = jdbc.query("SELECT id,date,start_time,end_time,rest_min,overtime_min,note " +
                        "FROM work_record WHERE user_id=? AND deleted=FALSE AND date>=? ORDER BY date DESC,id DESC",
                (result, row) -> new WorkEvent(result.getLong(1), result.getDate(2).toLocalDate(),
                        result.getTime(3).toLocalTime(),
                        result.getTime(4) == null ? null : result.getTime(4).toLocalTime(),
                        result.getInt(5), result.getInt(6), result.getString(7)), userId, Date.valueOf(since));
        String stamp = ICS_TIME.format(Instant.now());
        for (WorkEvent item : rows) {
            if (item.endTime() == null) continue;
            LocalDateTime start = item.day().atTime(item.startTime());
            LocalDateTime end = item.day().atTime(item.endTime());
            // 跨零点下班：结束时间早于开始时间时顺延一天
            if (!end.isAfter(start)) end = end.plusDays(1);
            StringBuilder description = new StringBuilder("工时记录");
            if (item.restMinutes() > 0) description.append("\n休息：").append(item.restMinutes()).append(" 分钟");
            if (item.overtimeMinutes() > 0) description.append("\n加班：").append(item.overtimeMinutes()).append(" 分钟");
            if (item.note() != null && !item.note().isBlank()) {
                description.append("\n备注：").append(item.note().trim());
            }
            out.append("BEGIN:VEVENT\r\nUID:work-").append(item.id()).append("@worktime\r\n")
                    .append("DTSTAMP:").append(stamp).append("\r\n")
                    .append("DTSTART:").append(ICS_TIME.format(start.atZone(zone).toInstant())).append("\r\n")
                    .append("DTEND:").append(ICS_TIME.format(end.atZone(zone).toInstant())).append("\r\n")
                    .append("SUMMARY:").append(escape("上班 " + item.startTime().toString().substring(0, 5)
                            + "–" + item.endTime().toString().substring(0, 5))).append("\r\n")
                    .append("CATEGORIES:").append(escape("工时")).append("\r\n")
                    .append("DESCRIPTION:").append(escape(description.toString())).append("\r\n")
                    // 工时是真实占用，保持忙碌状态
                    .append("TRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\n");
        }
    }

    private String countdownRule(String repeatRule, boolean repeatYearly) {
        String rule = repeatRule == null ? "" : repeatRule.trim().toUpperCase();
        if (rule.startsWith("CUSTOM:")) {
            try {
                int interval = Math.max(1, Integer.parseInt(rule.substring(7).trim()));
                return "FREQ=DAILY;INTERVAL=" + interval;
            } catch (NumberFormatException exception) {
                return "";
            }
        }
        return switch (rule) {
            case "DAILY", "WEEKLY", "MONTHLY", "YEARLY" -> "FREQ=" + rule;
            case "" -> repeatYearly ? "FREQ=YEARLY" : "";
            default -> repeatYearly ? "FREQ=YEARLY" : "";
        };
    }

    private String habitRule(String frequency, String customDays, int intervalDays) {
        if ("DAILY".equals(frequency)) return "FREQ=DAILY";
        if ("INTERVAL".equals(frequency)) return "FREQ=DAILY;INTERVAL=" + Math.max(1, intervalDays);
        if ("CUSTOM".equals(frequency)) {
            String byDay = weekDays(customDays);
            return byDay.isEmpty() ? "" : "FREQ=WEEKLY;BYDAY=" + byDay;
        }
        return "";
    }

    private String weekDays(String customDays) {
        if (customDays == null || customDays.isBlank()) return "";
        String[] names = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};
        StringBuilder out = new StringBuilder();
        for (String part : customDays.split(",")) {
            try {
                int day = Integer.parseInt(part.trim());
                if (day < 1 || day > 7) continue;
                if (out.length() > 0) out.append(',');
                out.append(names[day - 1]);
            } catch (NumberFormatException ignored) {
                // 脏数据直接跳过，不让整份订阅失败
            }
        }
        return out.toString();
    }

    private String habitDescription(HabitEvent item) {
        StringBuilder description = new StringBuilder();
        description.append(switch (item.frequency() == null ? "" : item.frequency()) {
            case "CUSTOM" -> "频率：每周 " + weekDays(item.customDays()).replace(",", "、");
            case "INTERVAL" -> "频率：每 " + Math.max(1, item.intervalDays()) + " 天";
            default -> "频率：每天";
        });
        if (item.targetCount() > 1) description.append("\n目标：每天 ").append(item.targetCount()).append(" 次");
        description.append("\n打卡请在 WorkBoard 里完成，这里只是提醒。");
        return description.toString();
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim());
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 账目流水：最近 90 天，每笔一条全天事件，标题带金额，分类进 CATEGORIES。
     * 只导真正的收支，账户间转账（TRANSFER / TRANSFER_IN / TRANSFER_OUT）跳过——
     * 同一笔钱在日历里出现两次且没有收支含义。
     */
    private void appendLedger(StringBuilder out, long userId, ZoneId zone) {
        LocalDate since = LocalDate.now(zone).minusDays(90);
        List<LedgerEvent> rows = jdbc.query("SELECT t.public_id,t.kind,t.amount,t.currency,t.occurred_on," +
                        "t.payee,t.note,t.member_name,COALESCE(c.name,''),COALESCE(a.name,'') " +
                        "FROM ledger_transaction t " +
                        "LEFT JOIN ledger_category c ON c.id=t.category_id " +
                        "LEFT JOIN ledger_account a ON a.id=t.account_id " +
                        "WHERE t.user_id=? AND t.deleted=FALSE AND t.occurred_on>=? " +
                        "AND t.kind IN ('EXPENSE','INCOME','BORROW_IN','LEND_OUT','COLLECT_DEBT','REPAY_DEBT') " +
                        "ORDER BY t.occurred_on DESC,t.id DESC",
                (result, row) -> new LedgerEvent(result.getString(1), result.getString(2),
                        result.getBigDecimal(3), result.getString(4), result.getDate(5).toLocalDate(),
                        result.getString(6), result.getString(7), result.getString(8),
                        result.getString(9), result.getString(10)), userId, Date.valueOf(since));
        String stamp = ICS_TIME.format(Instant.now());
        for (LedgerEvent item : rows) {
            boolean income = !"EXPENSE".equals(item.kind());
            String amount = money(item.currency(), item.amount());
            String label = item.payee() != null && !item.payee().isBlank() ? item.payee().trim()
                    : (!item.category().isBlank() ? item.category() : (income ? "收入" : "支出"));
            out.append("BEGIN:VEVENT\r\nUID:").append(item.publicId()).append("@ledger\r\n")
                    .append("DTSTAMP:").append(stamp).append("\r\n")
                    .append("DTSTART;VALUE=DATE:").append(ICS_DATE.format(item.day())).append("\r\n")
                    .append("DTEND;VALUE=DATE:").append(ICS_DATE.format(item.day().plusDays(1))).append("\r\n")
                    .append("SUMMARY:").append(escape(label + " " + (income ? "+" : "") + amount)).append("\r\n")
                    .append("CATEGORIES:").append(escape(item.category().isBlank()
                            ? (income ? "收入" : "支出") : item.category())).append("\r\n")
                    .append("DESCRIPTION:").append(escape(ledgerDescription(item, amount))).append("\r\n")
                    // 记账是已发生的事实，不该让日历显示为忙碌
                    .append("TRANSP:TRANSPARENT\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\n");
        }
    }

    private String ledgerDescription(LedgerEvent item, String amount) {
        StringBuilder description = new StringBuilder("金额：").append(amount)
                .append("（").append(switch (item.kind()) {
                    case "INCOME" -> "收入";
                    case "BORROW_IN" -> "借入";
                    case "LEND_OUT" -> "借出";
                    case "COLLECT_DEBT" -> "收回借款";
                    case "REPAY_DEBT" -> "还款";
                    default -> "支出";
                }).append("）");
        if (!item.category().isBlank()) description.append("\n分类：").append(item.category());
        if (!item.account().isBlank()) description.append("\n账户：").append(item.account());
        if (item.member() != null && !item.member().isBlank()) description.append("\n成员：").append(item.member());
        if (item.payee() != null && !item.payee().isBlank()) description.append("\n商户：").append(item.payee());
        if (item.note() != null && !item.note().isBlank()) description.append("\n备注：").append(item.note().trim());
        return description.toString();
    }

    private String money(String currency, java.math.BigDecimal amount) {
        String symbol = currency == null || "CNY".equals(currency) ? "¥" : currency + " ";
        return symbol + new java.text.DecimalFormat("#,##0.00").format(amount == null
                ? java.math.BigDecimal.ZERO : amount);
    }

    private String priorityLabel(String priority) {
        if (priority == null) return "";
        return switch (priority) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            default -> "";
        };
    }

    private ZoneId zone(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        } catch (Exception exception) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private FeedToken issue(String baseUrl) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hint = raw.substring(0, 4) + "…" + raw.substring(raw.length() - 4);
        jdbc.update("INSERT INTO task_feed_token(user_id,token_hash,token_hint,token_value) VALUES(?,?,?,?)",
                currentUser.id(), hash(raw), hint, raw);
        return new FeedToken(true, hint, url(baseUrl, raw), Instant.now().toString(), null);
    }

    private String url(String baseUrl, String raw) {
        return raw == null || raw.isBlank() ? null : baseUrl + "/api/v1/tasks/feed/" + raw + ".ics";
    }

    private void limit(String address, String hash) {
        long hour = Instant.now().getEpochSecond() / 3600;
        String key = (address == null ? "unknown" : address) + '|' + hash;
        Window window = limits.compute(key, (ignored, existing) -> existing == null || existing.hour() != hour
                ? new Window(hour, 1) : new Window(hour, existing.count() + 1));
        if (window.count() > 60) throw new IllegalStateException("订阅访问过于频繁，请稍后重试");
        if (limits.size() > 10000) limits.entrySet().removeIf(entry -> entry.getValue().hour() < hour - 1);
    }

    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法生成订阅摘要", exception); }
    }

    private String escape(String value) { return value.replace("\\", "\\\\").replace("\r", "")
            .replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;"); }
    private String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private record Owner(long id, long userId, boolean includeDescription, String timezone) { }
    private record FeedTask(long id, String publicId, String title, String description, Instant dueAt,
                            Instant startAt, boolean allDay, String priority, String listName,
                            int checklistTotal, int checklistDone) { }
    private record CountdownEvent(String publicId, String title, LocalDate day, String repeatRule,
                                  boolean repeatYearly, String preciseTime, String note, String group) { }
    private record HabitEvent(String publicId, String name, String frequency, String customDays, String remindAt,
                              LocalDate startDate, int intervalDays, int targetCount) { }
    private record WorkEvent(long id, LocalDate day, LocalTime startTime, LocalTime endTime,
                             int restMinutes, int overtimeMinutes, String note) { }
    private record LedgerEvent(String publicId, String kind, java.math.BigDecimal amount, String currency,
                               LocalDate day, String payee, String note, String member,
                               String category, String account) { }
    private record Window(long hour, int count) { }
}
