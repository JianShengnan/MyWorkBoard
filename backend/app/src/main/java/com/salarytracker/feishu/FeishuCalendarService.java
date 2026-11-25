package com.salarytracker.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把任务与倒数日写进飞书日历。
 *
 * <p>飞书不支持 ICS 网址订阅（「订阅日历」只搜飞书内的联系人和公共日历，「添加第三方日历」只认
 * Google/Exchange/Outlook 账号），所以只能由应用创建专属日历、共享给用户，再通过日历 API 维护日程。
 * 应用身份（tenant_access_token）即可完成全流程，不需要用户 OAuth。</p>
 *
 * <p>同步是「期望状态」式的：每次算出窗口内应该长什么样，与 {@code feishu_calendar_event} 里的
 * 映射比对——新实体创建、指纹变了 PATCH、本地没了 DELETE。指纹没变完全不调 API，所以即使每 5 分钟
 * 跑一次也不会打爆限流。</p>
 */
@Service
public class FeishuCalendarService {
    private static final Logger log = LoggerFactory.getLogger(FeishuCalendarService.class);

    /** 只同步这个窗口内的任务：太久远的翻不到，太远的堆在日历里也是噪音。 */
    private static final int TASK_PAST_DAYS = 7;
    private static final int TASK_FUTURE_DAYS = 45;
    private static final int COUNTDOWN_PAST_DAYS = 7;
    private static final int COUNTDOWN_FUTURE_DAYS = 365;
    /** 单次同步的事件上限。 */
    private static final int MAX_EVENTS = 500;
    /** 飞书日历接口对写操作限流很紧（实测 20ms 间隔十几条就 190010），200ms 一条稳得住。 */
    private static final long CALL_GAP_MILLIS = 200;
    private static final int MAX_RETRIES = 4;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;
    private final FeishuClient client;
    private final FeishuConfig config;

    public FeishuCalendarService(JdbcTemplate jdbc, FeishuClient client, FeishuConfig config) {
        this.jdbc = jdbc;
        this.client = client;
        this.config = config;
    }

    public record SyncStatus(boolean configured, boolean bound, boolean enabled, String calendarId,
                             String lastSyncedAt, int lastEventCount, String lastError,
                             boolean includeTasks, boolean includeCountdowns) {
    }

    public record SyncResult(String calendarId, int created, int updated, int deleted, int total) {
    }

    /** 期望状态里的一条日程。 */
    record Desired(String key, String summary, String description, boolean allDay,
                           LocalDate startDate, LocalDate endDate, Instant startAt, Instant endAt) {
        String fingerprint() {
            String raw = String.join("|", summary, description, String.valueOf(allDay),
                    String.valueOf(startDate), String.valueOf(endDate),
                    startAt == null ? "" : String.valueOf(startAt.getEpochSecond()),
                    endAt == null ? "" : String.valueOf(endAt.getEpochSecond()));
            return sha256(raw);
        }
    }

    private record Row(String eventKey, String eventId, String fingerprint) {
    }

    // ---------------------------------------------------------------- 查询

    public SyncStatus status(long userId) {
        List<SyncStatus> rows = jdbc.query("SELECT calendar_id,enabled,include_tasks,include_countdowns," +
                        "last_synced_at,last_event_count,last_error FROM feishu_calendar_sync WHERE user_id=?",
                (result, index) -> new SyncStatus(config.ready(), bound(userId) != null, result.getBoolean(2),
                        result.getString(1), text(result.getTimestamp(5)), result.getInt(6), result.getString(7),
                        result.getBoolean(3), result.getBoolean(4)), userId);
        if (!rows.isEmpty()) return rows.get(0);
        return new SyncStatus(config.ready(), bound(userId) != null, false, null, null, 0, null, true, true);
    }

    public SyncResult enable(long userId) {
        if (!config.ready()) throw new IllegalStateException("飞书应用凭证未配置");
        String openId = bound(userId);
        if (openId == null) throw new IllegalStateException("请先在飞书里绑定账号（发送「我的ID」或用绑定码）");
        String calendarId = calendarId(userId);
        if (calendarId == null) {
            calendarId = createCalendar();
            shareTo(calendarId, openId);
            jdbc.update("INSERT INTO feishu_calendar_sync(user_id,calendar_id,enabled) VALUES(?,?,TRUE)",
                    userId, calendarId);
            log.info("飞书日历已创建 user={} calendar={}", userId, calendarId);
        } else {
            jdbc.update("UPDATE feishu_calendar_sync SET enabled=TRUE WHERE user_id=?", userId);
        }
        SyncResult result = sync(userId);
        return new SyncResult(calendarId, result.created(), result.updated(), result.deleted(), result.total());
    }

    /** 关闭同步：日历留在飞书里（里面的日程一并保留），只是不再维护。 */
    public void disable(long userId) {
        jdbc.update("UPDATE feishu_calendar_sync SET enabled=FALSE WHERE user_id=?", userId);
    }

    public void updateScope(long userId, Boolean includeTasks, Boolean includeCountdowns) {
        if (includeTasks != null) {
            jdbc.update("UPDATE feishu_calendar_sync SET include_tasks=? WHERE user_id=?", includeTasks, userId);
        }
        if (includeCountdowns != null) {
            jdbc.update("UPDATE feishu_calendar_sync SET include_countdowns=? WHERE user_id=?", includeCountdowns, userId);
        }
    }

    // ---------------------------------------------------------------- 同步

    /** 定时入口：每 5 分钟把已开启同步的账号都推一遍。 */
    @Scheduled(initialDelay = 90_000, fixedDelayString = "${app.feishu.calendar.sync-interval-ms:300000}")
    public void syncAll() {
        if (!config.ready()) return;
        List<Long> users = jdbc.queryForList(
                "SELECT s.user_id FROM feishu_calendar_sync s JOIN feishu_binding b ON b.user_id=s.user_id " +
                        "WHERE s.enabled=TRUE", Long.class);
        for (Long userId : users) {
            try {
                sync(userId);
            } catch (RuntimeException exception) {
                log.warn("飞书日历同步失败 user={}：{}", userId, exception.getMessage());
            }
        }
    }

    public synchronized SyncResult sync(long userId) {
        List<Object[]> configs = jdbc.query("SELECT calendar_id,enabled,include_tasks,include_countdowns " +
                        "FROM feishu_calendar_sync WHERE user_id=?",
                (result, index) -> new Object[]{result.getString(1), result.getBoolean(2),
                        result.getBoolean(3), result.getBoolean(4)}, userId);
        if (configs.isEmpty()) return new SyncResult(null, 0, 0, 0, 0);
        Object[] settings = configs.get(0);
        String calendarId = (String) settings[0];
        if (!Boolean.TRUE.equals(settings[1])) return new SyncResult(calendarId, 0, 0, 0, 0);

        try {
            ZoneId zone = zone(userId);
            List<Desired> desired = new ArrayList<>();
            if (Boolean.TRUE.equals(settings[2])) desired.addAll(taskEvents(userId, zone));
            if (Boolean.TRUE.equals(settings[3])) desired.addAll(countdownEvents(userId, zone));
            if (desired.size() > MAX_EVENTS) desired = new ArrayList<>(desired.subList(0, MAX_EVENTS));

            Map<String, Row> existing = new LinkedHashMap<>();
            jdbc.query("SELECT event_key,feishu_event_id,fingerprint FROM feishu_calendar_event WHERE user_id=?",
                    (result, index) -> {
                        existing.put(result.getString(1),
                                new Row(result.getString(1), result.getString(2), result.getString(3)));
                        return null;
                    }, userId);

            int created = 0;
            int updated = 0;
            int deleted = 0;
            Set<String> kept = new HashSet<>();
            for (Desired item : desired) {
                kept.add(item.key());
                Row row = existing.get(item.key());
                String fingerprint = item.fingerprint();
                if (row == null) {
                    String eventId = withRetry(() -> createEvent(calendarId, item, zone));
                    jdbc.update("INSERT INTO feishu_calendar_event(user_id,event_key,feishu_event_id,fingerprint) " +
                                    "VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE feishu_event_id=?,fingerprint=?",
                            userId, item.key(), eventId, fingerprint, eventId, fingerprint);
                    created += 1;
                    pause();
                } else if (!fingerprint.equals(row.fingerprint())) {
                    withRetry(() -> {
                        patchEvent(calendarId, row.eventId(), item, zone);
                        return null;
                    });
                    jdbc.update("UPDATE feishu_calendar_event SET fingerprint=?,synced_at=CURRENT_TIMESTAMP(3) " +
                            "WHERE user_id=? AND event_key=?", fingerprint, userId, item.key());
                    updated += 1;
                    pause();
                }
            }
            for (Row row : existing.values()) {
                if (kept.contains(row.eventKey())) continue;
                withRetry(() -> {
                    deleteEvent(calendarId, row.eventId());
                    return null;
                });
                jdbc.update("DELETE FROM feishu_calendar_event WHERE user_id=? AND event_key=?",
                        userId, row.eventKey());
                deleted += 1;
                pause();
            }

            jdbc.update("UPDATE feishu_calendar_sync SET last_synced_at=CURRENT_TIMESTAMP(3),last_event_count=?," +
                    "last_error=NULL WHERE user_id=?", desired.size(), userId);
            log.info("飞书日历同步完成 user={} +{} ~{} -{} 共{}", userId, created, updated, deleted, desired.size());
            return new SyncResult(calendarId, created, updated, deleted, desired.size());
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE feishu_calendar_sync SET last_error=? WHERE user_id=?",
                    shorten(exception.getMessage()), userId);
            throw exception;
        }
    }

    // ---------------------------------------------------------------- 事件组装

    List<Desired> taskEvents(long userId, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(TASK_PAST_DAYS).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(TASK_FUTURE_DAYS).atStartOfDay(zone).toInstant();
        return jdbc.query("SELECT t.public_id,t.title,t.description,t.start_at,t.due_at,t.all_day,t.priority," +
                        "l.name," +
                        "(SELECT COUNT(*) FROM task_checklist_item c WHERE c.task_id=t.id AND c.deleted=FALSE)," +
                        "(SELECT COUNT(*) FROM task_checklist_item c WHERE c.task_id=t.id AND c.deleted=FALSE" +
                        " AND c.completed=TRUE) " +
                        "FROM task t JOIN task_list l ON l.id=t.list_id " +
                        "WHERE t.user_id=? AND t.deleted=FALSE AND l.deleted=FALSE AND l.archived=FALSE" +
                        " AND t.status='OPEN' AND t.due_at IS NOT NULL" +
                        " AND t.due_at>=? AND COALESCE(LEAST(t.start_at,t.due_at),t.due_at)<? " +
                        "ORDER BY t.due_at,t.id",
                (result, index) -> {
                    String publicId = result.getString(1);
                    String title = result.getString(2);
                    String description = result.getString(3);
                    Timestamp startStamp = result.getTimestamp(4);
                    Instant due = result.getTimestamp(5).toInstant();
                    boolean allDay = result.getBoolean(6);
                    String priority = result.getString(7);
                    String listName = result.getString(8);
                    int total = result.getInt(9);
                    int done = result.getInt(10);
                    Instant start = startStamp == null ? null : startStamp.toInstant();

                    StringBuilder note = new StringBuilder("清单：").append(listName == null ? "收集箱" : listName);
                    if (!"NONE".equals(priority) && priority != null) {
                        note.append("\n优先级：").append(switch (priority) {
                            case "HIGH" -> "高";
                            case "MEDIUM" -> "中";
                            case "LOW" -> "低";
                            default -> priority;
                        });
                    }
                    if (total > 0) note.append("\n检查项：").append(done).append('/').append(total);
                    if (description != null && !description.isBlank()) {
                        note.append("\n\n").append(description.trim().length() > 300
                                ? description.trim().substring(0, 300) + "…" : description.trim());
                    }

                    String key = "task:" + publicId;
                    if (allDay) {
                        LocalDate first = (start == null ? due : start).atZone(zone).toLocalDate();
                        LocalDate last = due.atZone(zone).toLocalDate();
                        if (last.isBefore(first)) last = first;
                        return new Desired(key, title, note.toString(), true, first, last.plusDays(1), null, null);
                    }
                    if (start != null && start.isBefore(due)) {
                        return new Desired(key, title, note.toString(), false, null, null, start, due);
                    }
                    return new Desired(key, title, note.toString(), false, null, null, due, due.plusSeconds(1800));
                }, userId, Timestamp.from(from), Timestamp.from(to));
    }

    List<Desired> countdownEvents(long userId, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        return jdbc.query("SELECT c.public_id,c.title,c.target_date,c.precise_time,c.note,COALESCE(g.name,'') " +
                        "FROM countdown c LEFT JOIN countdown_group g ON g.public_id=c.group_id AND g.deleted_at IS NULL " +
                        "WHERE c.user_id=? AND c.deleted_at IS NULL AND c.archived=0 AND c.target_date>=? " +
                        "AND c.target_date<? ORDER BY c.target_date,c.id",
                (result, index) -> {
                    String publicId = result.getString(1);
                    String title = result.getString(2);
                    LocalDate day = result.getDate(3).toLocalDate();
                    String precise = result.getString(4);
                    String note = result.getString(5);
                    String group = result.getString(6);
                    StringBuilder description = new StringBuilder();
                    if (group != null && !group.isBlank()) description.append("分组：").append(group);
                    if (note != null && !note.isBlank()) {
                        if (description.length() > 0) description.append('\n');
                        description.append(note.trim());
                    }
                    String key = "countdown:" + publicId + ":" + DATE.format(day);
                    LocalTime time = parseTime(precise);
                    if (time == null) {
                        return new Desired(key, title, description.toString(), true, day, day.plusDays(1), null, null);
                    }
                    Instant at = day.atTime(time).atZone(zone).toInstant();
                    return new Desired(key, title, description.toString(), false, null, null, at, at.plusSeconds(1800));
                }, userId, java.sql.Date.valueOf(today.minusDays(COUNTDOWN_PAST_DAYS)),
                java.sql.Date.valueOf(today.plusDays(COUNTDOWN_FUTURE_DAYS)));
    }

    // ---------------------------------------------------------------- 飞书调用

    private String createCalendar() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", "WorkBoard");
        body.put("description", "任务与倒数日（来自 WorkBoard 网站）");
        body.put("permissions", "private");
        body.put("color", -1);
        JsonNode data = client.apiPost("/calendar/v4/calendars", body);
        String id = data.path("calendar").path("calendar_id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("飞书未返回 calendar_id");
        return id;
    }

    private void shareTo(String calendarId, String openId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", "owner");
        body.put("scope", Map.of("type", "user", "user_id", openId));
        client.apiPost("/calendar/v4/calendars/" + calendarId + "/acls", body);
    }

    private String createEvent(String calendarId, Desired item, ZoneId zone) {
        JsonNode data = client.apiPost("/calendar/v4/calendars/" + calendarId + "/events", eventBody(item, zone));
        String id = data.path("event").path("event_id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("飞书未返回 event_id：" + item.summary());
        return id;
    }

    private void patchEvent(String calendarId, String eventId, Desired item, ZoneId zone) {
        client.apiPatch("/calendar/v4/calendars/" + calendarId + "/events/" + eventId, eventBody(item, zone));
    }

    private void deleteEvent(String calendarId, String eventId) {
        client.apiDelete("/calendar/v4/calendars/" + calendarId + "/events/" + eventId + "?need_notification=false");
    }

    private Map<String, Object> eventBody(Desired item, ZoneId zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", item.summary());
        if (item.description() != null && !item.description().isBlank()) {
            body.put("description", item.description());
        }
        if (item.allDay()) {
            body.put("start_time", Map.of("date", DATE.format(item.startDate())));
            // 全天事件的结束日期是「不含」的次日，写错会多占一天
            body.put("end_time", Map.of("date", DATE.format(item.endDate())));
            body.put("is_all_day", true);
        } else {
            String timezone = zone.getId();
            body.put("start_time", Map.of("timestamp", String.valueOf(item.startAt().getEpochSecond()),
                    "timezone", timezone));
            body.put("end_time", Map.of("timestamp", String.valueOf(item.endAt().getEpochSecond()),
                    "timezone", timezone));
            body.put("is_all_day", false);
        }
        body.put("free_busy_status", "free");
        return body;
    }

    // ---------------------------------------------------------------- 工具

    private String calendarId(long userId) {
        List<String> rows = jdbc.query("SELECT calendar_id FROM feishu_calendar_sync WHERE user_id=?",
                (result, index) -> result.getString(1), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String bound(long userId) {
        List<String> rows = jdbc.query("SELECT open_id FROM feishu_binding WHERE user_id=?",
                (result, index) -> result.getString(1), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ZoneId zone(long userId) {
        List<String> rows = jdbc.query("SELECT timezone FROM task_setting WHERE user_id=?",
                (result, index) -> result.getString(1), userId);
        String value = rows.isEmpty() ? null : rows.get(0);
        if (value == null || value.isBlank()) return ZoneId.of("Asia/Shanghai");
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.length() > 5 ? value.substring(0, 5) : value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String text(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static String shorten(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("指纹计算失败", exception);
        }
    }

    /**
     * 写操作遇到限流时退避重试：飞书日历 API 的 QPS 限制比 IM 接口低得多，
     * 一次同步几十条必然撞上 190010。POST 失败时事件并未创建，重试不会重复；PATCH/DELETE 本身幂等。
     */
    private <T> T withRetry(java.util.function.Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt += 1) {
            try {
                return action.get();
            } catch (RuntimeException exception) {
                last = exception;
                if (!rateLimited(exception)) throw exception;
                long wait = 800L * (attempt + 1) * (attempt + 1);
                log.debug("飞书日历限流，{} ms 后重试", wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
        throw last == null ? new IllegalStateException("飞书日历调用失败") : last;
    }

    private static boolean rateLimited(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null) return false;
        return message.contains("190010") || message.contains("429") || message.contains("rate limited");
    }

    private static void pause() {
        try {
            Thread.sleep(CALL_GAP_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
