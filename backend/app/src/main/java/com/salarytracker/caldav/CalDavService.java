package com.salarytracker.caldav;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
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
 * CalDAV 单向导出：任务与倒数日 → Radicale。
 *
 * <p>为什么不用 ICS 订阅：订阅是只读的一次性投影，客户端不能改，也拿不到「改了回写」的能力。
 * CalDAV 是协议而不是某个厂商的功能，iOS 日历 / DAVx5 / Thunderbird 都原生支持，
 * 后续把客户端修改回写网站也只需要同一套通道。</p>
 *
 * <p>同步是期望状态式的：每个实体一个 .ics（UID 稳定 = 客户端不会重复建事件），
 * 内容指纹没变就不 PUT，网站删了才 DELETE。</p>
 */
@Service
public class CalDavService {
    private static final Logger log = LoggerFactory.getLogger(CalDavService.class);

    /** 只导出这个窗口，避免一次性把几年历史灌进客户端。 */
    private static final int TASK_PAST_DAYS = 7;
    private static final int TASK_FUTURE_DAYS = 60;
    private static final int COUNTDOWN_PAST_DAYS = 7;
    private static final int COUNTDOWN_FUTURE_DAYS = 365;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneId.of("UTC"));

    private final JdbcTemplate jdbc;
    private final RestClient client;
    private final String baseUrl;
    private final String username;
    private final String password;

    public CalDavService(JdbcTemplate jdbc,
                         @Value("${app.caldav.url:}") String baseUrl,
                         @Value("${app.caldav.username:}") String username,
                         @Value("${app.caldav.password:}") String password) {
        this.jdbc = jdbc;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public record SyncStatus(boolean configured, boolean enabled, String url, String username,
                             String collection, String lastSyncedAt, int lastEventCount, String lastError) {
    }

    public record SyncResult(boolean ok, int created, int updated, int deleted, int total, String detail) {
    }

    record Desired(String uid, String kind, String ics, String fingerprint) {
    }

    private record Row(String uid, String kind, String fingerprint) {
    }

    // ---------------------------------------------------------------- 状态与开关

    public boolean configured() {
        return !baseUrl.isEmpty() && !username.isEmpty();
    }

    public SyncStatus status(long userId) {
        List<SyncStatus> rows = jdbc.query("SELECT enabled,collection_path,last_synced_at,last_event_count,last_error " +
                        "FROM caldav_sync WHERE user_id=?",
                (result, index) -> new SyncStatus(configured(), result.getBoolean(1), publicUrl(),
                        username, result.getString(2), text(result.getTimestamp(3)),
                        result.getInt(4), result.getString(5)), userId);
        if (!rows.isEmpty()) return rows.get(0);
        return new SyncStatus(configured(), true, publicUrl(), username, "/workboard/workboard/", null, 0, null);
    }

    /** 客户端要用公网地址（带 /dav 前缀），后端内部走 salary-radicale:5232。 */
    public String publicUrl() {
        return configured() ? "https://jsn1024.cn/dav/" : "";
    }

    public void setEnabled(long userId, boolean enabled) {
        ensureRow(userId);
        jdbc.update("UPDATE caldav_sync SET enabled=?,revision=revision+1 WHERE user_id=?", enabled, userId);
    }

    private void ensureRow(long userId) {
        jdbc.update("INSERT IGNORE INTO caldav_sync(user_id,enabled) VALUES(?,TRUE)", userId);
    }

    private String collection(long userId) {
        List<String> rows = jdbc.query("SELECT collection_path FROM caldav_sync WHERE user_id=?",
                (result, index) -> result.getString(1), userId);
        return rows.isEmpty() ? "/workboard/workboard/" : rows.get(0);
    }

    // ---------------------------------------------------------------- 同步

    @Scheduled(initialDelay = 120_000, fixedDelayString = "${app.caldav.sync-interval-ms:300000}")
    public void syncAll() {
        if (!configured()) return;
        List<Long> users = jdbc.queryForList("SELECT user_id FROM caldav_sync WHERE enabled=TRUE", Long.class);
        for (Long userId : users) {
            try {
                sync(userId);
            } catch (RuntimeException exception) {
                log.warn("CalDAV 同步失败 user={}：{}", userId, exception.getMessage());
            }
        }
    }

    public synchronized SyncResult sync(long userId) {
        if (!configured()) return new SyncResult(false, 0, 0, 0, 0, "服务端未配置 CalDAV");
        ensureRow(userId);
        try {
            ZoneId zone = zone(userId);
            String collection = collection(userId);
            List<Desired> desired = new ArrayList<>(taskEvents(userId, zone));
            desired.addAll(countdownEvents(userId, zone));

            Map<String, Row> existing = new LinkedHashMap<>();
            jdbc.query("SELECT event_uid,kind,fingerprint FROM caldav_event WHERE user_id=?",
                    (result, index) -> {
                        existing.put(result.getString(1),
                                new Row(result.getString(1), result.getString(2), result.getString(3)));
                        return null;
                    }, userId);

            int created = 0;
            int updated = 0;
            int deleted = 0;
            int failed = 0;
            Set<String> kept = new HashSet<>();
            for (Desired item : desired) {
                kept.add(item.uid());
                Row row = existing.get(item.uid());
                try {
                    if (row == null) {
                        put(collection, item);
                        jdbc.update("INSERT INTO caldav_event(user_id,event_uid,kind,fingerprint) VALUES(?,?,?,?) " +
                                        "ON DUPLICATE KEY UPDATE fingerprint=?,kind=?",
                                userId, item.uid(), item.kind(), item.fingerprint(), item.fingerprint(), item.kind());
                        created += 1;
                    } else if (!item.fingerprint().equals(row.fingerprint())) {
                        put(collection, item);
                        jdbc.update("UPDATE caldav_event SET fingerprint=?,kind=?,synced_at=CURRENT_TIMESTAMP(3) " +
                                "WHERE user_id=? AND event_uid=?", item.fingerprint(), item.kind(), userId, item.uid());
                        updated += 1;
                    }
                } catch (RuntimeException exception) {
                    failed += 1;
                    log.warn("CalDAV 写入失败 uid={}：{}", item.uid(), exception.getMessage());
                }
            }
            for (Row row : existing.values()) {
                if (kept.contains(row.uid())) continue;
                try {
                    delete(collection, row.uid());
                    jdbc.update("DELETE FROM caldav_event WHERE user_id=? AND event_uid=?", userId, row.uid());
                    deleted += 1;
                } catch (RuntimeException exception) {
                    failed += 1;
                    log.warn("CalDAV 删除失败 uid={}：{}", row.uid(), exception.getMessage());
                }
            }

            jdbc.update("UPDATE caldav_sync SET last_synced_at=CURRENT_TIMESTAMP(3),last_event_count=?," +
                            "last_error=? WHERE user_id=?",
                    desired.size(), failed == 0 ? null : (failed + " 条失败，详见日志"), userId);
            log.info("CalDAV 同步完成 user={} +{} ~{} -{} 失败{} 共{}",
                    userId, created, updated, deleted, failed, desired.size());
            return new SyncResult(failed == 0, created, updated, deleted, desired.size(),
                    failed == 0 ? "同步完成" : failed + " 条失败");
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE caldav_sync SET last_error=? WHERE user_id=?",
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
                        "t.revision,l.name," +
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
                    int revision = result.getInt(8);
                    String listName = result.getString(9);
                    int total = result.getInt(10);
                    int done = result.getInt(11);
                    Instant start = startStamp == null ? null : startStamp.toInstant();

                    String note = note(listName, priority, total, done, description);
                    String uid = "task-" + publicId + "@workboard";
                    String summary = title == null ? "任务" : title;

                    String fingerprint;
                    String ics;
                    if (allDay) {
                        LocalDate first = (start == null ? due : start).atZone(zone).toLocalDate();
                        LocalDate last = due.atZone(zone).toLocalDate();
                        if (last.isBefore(first)) last = first;
                        fingerprint = sha256(String.join("|", "V", summary, note,
                                first.toString(), last.toString()));
                        ics = allDayEvent(uid, summary, note, first, last, revision);
                    } else {
                        Instant endsAt = (start != null && start.isBefore(due)) ? due : due.plusSeconds(1800);
                        Instant beginsAt = (start != null && start.isBefore(due)) ? start : due;
                        fingerprint = sha256(String.join("|", "T", summary, note,
                                String.valueOf(beginsAt.getEpochSecond()), String.valueOf(endsAt.getEpochSecond())));
                        ics = timedEvent(uid, summary, note, beginsAt, endsAt, revision);
                    }
                    return new Desired(uid, "TASK", ics, fingerprint);
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
                    String uid = "countdown-" + publicId + "@workboard";
                    String summary = title == null ? "倒数日" : title;
                    StringBuilder description = new StringBuilder();
                    if (group != null && !group.isBlank()) description.append("分组：").append(group);
                    if (note != null && !note.isBlank()) {
                        if (description.length() > 0) description.append('\n');
                        description.append(note.trim());
                    }
                    LocalTime time = parseTime(precise);
                    if (time == null) {
                        return new Desired(uid, "COUNTDOWN",
                                allDayEvent(uid, summary, description.toString(), day, day, 0),
                                sha256(String.join("|", "CD", summary, description.toString(), day.toString())));
                    }
                    Instant at = day.atTime(time).atZone(zone).toInstant();
                    return new Desired(uid, "COUNTDOWN",
                            timedEvent(uid, summary, description.toString(), at, at.plusSeconds(1800), 0),
                            sha256(String.join("|", "CDT", summary, description.toString(),
                                    String.valueOf(at.getEpochSecond()))));
                }, userId, java.sql.Date.valueOf(today.minusDays(COUNTDOWN_PAST_DAYS)),
                java.sql.Date.valueOf(today.plusDays(COUNTDOWN_FUTURE_DAYS)));
    }

    private String note(String listName, String priority, int total, int done, String description) {
        StringBuilder note = new StringBuilder("清单：").append(listName == null ? "收集箱" : listName);
        if (priority != null && !"NONE".equals(priority)) {
            note.append("\n优先级：").append(switch (priority) {
                case "HIGH" -> "高";
                case "MEDIUM" -> "中";
                case "LOW" -> "低";
                default -> priority;
            });
        }
        if (total > 0) note.append("\n检查项：").append(done).append('/').append(total);
        if (description != null && !description.isBlank()) {
            String trimmed = description.trim();
            note.append("\n\n").append(trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed);
        }
        return note.toString();
    }

    /**
     * 全天事件：iCal 的 DTEND 是**不含**的次日，所以结束日要 +1。
     * 这与飞书日历 API 的闭区间语义正好相反，两边不能共用同一套日期计算。
     */
    private String allDayEvent(String uid, String summary, String description,
                               LocalDate first, LocalDate last, int revision) {
        StringBuilder sb = calendar();
        sb.append("BEGIN:VEVENT\r\n")
                .append("UID:").append(uid).append("\r\n")
                .append("DTSTAMP:").append(UTC.format(Instant.now())).append("\r\n")
                .append("SEQUENCE:").append(Math.max(0, revision)).append("\r\n")
                .append("SUMMARY:").append(escape(summary)).append("\r\n");
        if (description != null && !description.isBlank()) {
            sb.append("DESCRIPTION:").append(escape(description)).append("\r\n");
        }
        sb.append("DTSTART;VALUE=DATE:").append(DATE.format(first)).append("\r\n")
                .append("DTEND;VALUE=DATE:").append(DATE.format(last.plusDays(1))).append("\r\n")
                .append("TRANSP:TRANSPARENT\r\n")
                .append("STATUS:CONFIRMED\r\n")
                .append("END:VEVENT\r\n")
                .append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private String timedEvent(String uid, String summary, String description,
                              Instant beginsAt, Instant endsAt, int revision) {
        StringBuilder sb = calendar();
        sb.append("BEGIN:VEVENT\r\n")
                .append("UID:").append(uid).append("\r\n")
                .append("DTSTAMP:").append(UTC.format(Instant.now())).append("\r\n")
                .append("SEQUENCE:").append(Math.max(0, revision)).append("\r\n")
                .append("SUMMARY:").append(escape(summary)).append("\r\n");
        if (description != null && !description.isBlank()) {
            sb.append("DESCRIPTION:").append(escape(description)).append("\r\n");
        }
        // 用 UTC 而不是 TZID：省掉 VTIMEZONE 定义，客户端会按本地时区正确换算
        sb.append("DTSTART:").append(UTC.format(beginsAt)).append("\r\n")
                .append("DTEND:").append(UTC.format(endsAt)).append("\r\n")
                .append("TRANSP:TRANSPARENT\r\n")
                .append("STATUS:CONFIRMED\r\n")
                .append("END:VEVENT\r\n")
                .append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private StringBuilder calendar() {
        return new StringBuilder("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:-//WorkBoard//CalDAV//CN\r\n")
                .append("CALSCALE:GREGORIAN\r\n")
                .append("METHOD:PUBLISH\r\n");
    }

    // ---------------------------------------------------------------- CalDAV 调用

    private void put(String collection, Desired item) {
        String path = collection.endsWith("/") ? collection : collection + "/";
        String url = baseUrl + path + item.uid() + ".ics";
        String body = fold(item.ics());
        client.put().uri(url)
                .header("Authorization", authorization())
                .header("Content-Type", "text/calendar; charset=utf-8")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private void delete(String collection, String uid) {
        String path = collection.endsWith("/") ? collection : collection + "/";
        String url = baseUrl + path + uid + ".ics";
        try {
            client.delete().uri(url)
                    .header("Authorization", authorization())
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ignored) {
            // 本地记录还在但服务端已没有，视为删除成功
        }
    }

    private String authorization() {
        String raw = username + ":" + password;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 工具

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

    /** RFC 5545 转义：反斜杠、分号、逗号、换行。 */
    static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    /**
     * 按 75 字节折行（中文一个字符 3 字节，不能按字符数算），
     * 续行以单个空格开头。不折行时部分客户端会截断长描述。
     */
    static String fold(String ics) {
        StringBuilder out = new StringBuilder(ics.length() + 64);
        for (String line : ics.split("\r\n", -1)) {
            if (line.isEmpty()) continue;
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= 73) {
                out.append(line).append("\r\n");
                continue;
            }
            int offset = 0;
            boolean first = true;
            while (offset < line.length()) {
                int take = Math.min(first ? 73 : 72, line.length() - offset);
                // 确保不超过字节上限（3 字节字符最多回退 2 个）
                while (take > 1 && line.substring(offset, offset + take)
                        .getBytes(StandardCharsets.UTF_8).length > (first ? 73 : 72)) {
                    take -= 1;
                }
                if (!first) out.append(' ');
                out.append(line, offset, offset + take).append("\r\n");
                offset += take;
                first = false;
            }
        }
        return out.toString();
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

    static String sha256(String value) {
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

}
