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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

    public FeedToken status() {
        List<FeedToken> rows = jdbc.query("SELECT token_hint,created_at,last_accessed_at FROM task_feed_token " +
                        "WHERE user_id=? AND revoked_at IS NULL ORDER BY id DESC LIMIT 1",
                (result, row) -> new FeedToken(true, result.getString(1), null,
                        instant(result.getTimestamp(2)), instant(result.getTimestamp(3))), currentUser.id());
        return rows.isEmpty() ? new FeedToken(false, null, null, null, null) : rows.get(0);
    }

    @Transactional
    public FeedToken create(String baseUrl) {
        if (status().active()) throw new IllegalStateException("已有有效订阅地址，请使用轮换");
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
                .append(escape("WorkBoard 任务")).append("\r\n");
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
        jdbc.update("INSERT INTO task_feed_token(user_id,token_hash,token_hint) VALUES(?,?,?)",
                currentUser.id(), hash(raw), hint);
        return new FeedToken(true, hint, baseUrl + "/api/v1/tasks/feed/" + raw + ".ics",
                Instant.now().toString(), null);
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
    private record Window(long hour, int count) { }
}
