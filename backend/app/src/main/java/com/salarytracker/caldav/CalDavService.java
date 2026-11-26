package com.salarytracker.caldav;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;
import com.salarytracker.identity.CurrentUser;
import com.salarytracker.task.TaskModels;
import com.salarytracker.task.TaskService;

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
    private final TaskService tasks;

    public CalDavService(JdbcTemplate jdbc, TaskService tasks,
                         @Value("${app.caldav.url:}") String baseUrl,
                         @Value("${app.caldav.username:}") String username,
                         @Value("${app.caldav.password:}") String password) {
        this.jdbc = jdbc;
        this.tasks = tasks;
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

    private record Row(String uid, String kind, String fingerprint, String etag) {
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
            jdbc.query("SELECT event_uid,kind,fingerprint,etag FROM caldav_event WHERE user_id=?",
                    (result, index) -> {
                        existing.put(result.getString(1),
                                new Row(result.getString(1), result.getString(2), result.getString(3),
                                        result.getString(4)));
                        return null;
                    }, userId);

            // 先拉远端：客户端改过的先回写，再做本地导出，避免两边互相覆盖
            int pulled = pull(userId, collection, zone);

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
                        String pushedEtag = put(collection, item);
                        jdbc.update("INSERT INTO caldav_event(user_id,event_uid,kind,fingerprint,etag) VALUES(?,?,?,?,?) " +
                                        "ON DUPLICATE KEY UPDATE fingerprint=?,kind=?,etag=?",
                                userId, item.uid(), item.kind(), item.fingerprint(), pushedEtag,
                                item.fingerprint(), item.kind(), pushedEtag);
                        created += 1;
                    } else if (!item.fingerprint().equals(row.fingerprint())) {
                        String pushedEtag = put(collection, item);
                        jdbc.update("UPDATE caldav_event SET fingerprint=?,kind=?,etag=?,synced_at=CURRENT_TIMESTAMP(3) " +
                                "WHERE user_id=? AND event_uid=?", item.fingerprint(), item.kind(), pushedEtag, userId, item.uid());
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
            log.info("CalDAV 同步完成 user={} +{} ~{} -{} 回写{} 失败{} 共{}",
                    userId, created, updated, deleted, pulled, failed, desired.size());
            String detail = (failed == 0 ? "同步完成" : failed + " 条失败")
                    + (pulled > 0 ? "，回写 " + pulled + " 条" : "");
            return new SyncResult(failed == 0, created, updated, deleted, desired.size(), detail);
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


    // ---------------------------------------------------------------- 回写（客户端 → 网站）

    /**
     * 拉远端 etag，只有「etag 变了」的事件才回读内容。
     *
     * <p>判断依据是 etag 而不是「内容 hash」：我们自己 PUT 之后也会拿到新 etag，
     * 处理完把它记下来，下一轮就不会再进这里。真正需要防的是来回振荡，
     * 所以在回写前会逐字段比对，完全一致就只更新 etag、不写库。</p>
     */
    private int pull(long userId, String collection, ZoneId zone) {
        Map<String, String> etags;
        try {
            etags = propfindEtags(collection);
        } catch (RuntimeException exception) {
            log.warn("CalDAV 拉取远端事件失败：{}", exception.getMessage());
            return 0;
        }
        int written = 0;
        for (Map.Entry<String, String> entry : etags.entrySet()) {
            String uid = uidOf(entry.getKey());
            if (uid == null || uid.startsWith("countdown-")) continue;
            String etag = entry.getValue();
            List<Row> rows = jdbc.query("SELECT event_uid,kind,fingerprint,etag FROM caldav_event " +
                            "WHERE user_id=? AND event_uid=?",
                    (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                    userId, uid);
            if (rows.isEmpty()) continue;
            Row row = rows.get(0);
            if (etag != null && etag.equals(row.etag())) continue;

            String ics = fetch(collection, uid);
            if (ics == null) continue;
            boolean changed = false;
            try {
                changed = apply(userId, uid, ics, zone);
            } catch (RuntimeException exception) {
                log.warn("CalDAV 回写失败 uid={}：{}", uid, exception.getMessage());
            }
            if (changed) written += 1;
            jdbc.update("UPDATE caldav_event SET etag=? WHERE user_id=? AND event_uid=?", etag, userId, uid);
        }
        return written;
    }

    private Map<String, String> propfindEtags(String collection) {
        String path = collection.endsWith("/") ? collection : collection + "/";
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>""";
        var response = client.method(org.springframework.http.HttpMethod.valueOf("PROPFIND"))
                .uri(baseUrl + path)
                .header("Authorization", authorization())
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .body(body)
                .retrieve()
                .toEntity(String.class);
        Map<String, String> result = new LinkedHashMap<>();
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(
                            response.getBody() == null ? new byte[0]
                                    : response.getBody().getBytes(StandardCharsets.UTF_8)));
            var responses = document.getElementsByTagNameNS("DAV:", "response");
            for (int i = 0; i < responses.getLength(); i += 1) {
                var element = (org.w3c.dom.Element) responses.item(i);
                var hrefs = element.getElementsByTagNameNS("DAV:", "href");
                var etags = element.getElementsByTagNameNS("DAV:", "getetag");
                if (hrefs.getLength() == 0 || etags.getLength() == 0) continue;
                String href = hrefs.item(0).getTextContent();
                String etag = etags.item(0).getTextContent();
                if (href == null || !href.endsWith(".ics")) continue;
                result.put(href, etag == null ? "" : etag.replace("\"", "").trim());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("解析 PROPFIND 失败：" + exception.getMessage(), exception);
        }
        return result;
    }

    private String fetch(String collection, String uid) {
        String path = collection.endsWith("/") ? collection : collection + "/";
        try {
            var response = client.get().uri(baseUrl + path + uid + ".ics")
                    .header("Authorization", authorization())
                    .retrieve()
                    .toEntity(String.class);
            return response.getBody();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 把远端事件写回任务；完全一致时返回 false（只更新 etag，不写库）。 */
    private boolean apply(long userId, String uid, String ics, ZoneId zone) {
        Parsed parsed = parse(ics);
        if (parsed == null || parsed.summary() == null) return false;
        String publicId = uid.substring("task-".length(), uid.indexOf('@'));
        List<Object[]> rows = jdbc.query(
                "SELECT id,title,start_at,due_at,all_day FROM task WHERE public_id=? AND user_id=? AND deleted=FALSE",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4), rs.getBoolean(5)},
                publicId, userId);
        if (rows.isEmpty()) return false;
        Object[] row = rows.get(0);
        String currentTitle = (String) row[1];
        java.time.Instant currentStart = row[2] == null ? null : ((Timestamp) row[2]).toInstant();
        java.time.Instant currentDue = row[3] == null ? null : ((Timestamp) row[3]).toInstant();
        boolean currentAllDay = (Boolean) row[4];

        java.time.Instant newStart;
        java.time.Instant newDue;
        if (parsed.allDay()) {
            LocalDate startDay = LocalDate.parse(parsed.dtStart(), DATE);
            LocalDate endDay = parsed.dtEnd() == null ? startDay : LocalDate.parse(parsed.dtEnd(), DATE);
            newStart = startDay.atStartOfDay(zone).toInstant();
            // iCal 的 DTEND 是不含的次日，减一天才是占用的最后一天
            newDue = endDay.minusDays(1).atStartOfDay(zone).toInstant();
        } else {
            newStart = parseInstant(parsed.dtStart());
            newDue = parseInstant(parsed.dtEnd());
            if (newDue == null) newDue = newStart;
        }
        if (newStart == null) return false;

        boolean sameTitle = parsed.summary().equals(currentTitle);
        boolean sameStart = currentStart != null && currentStart.equals(newStart);
        boolean sameDue = currentDue != null && currentDue.equals(newDue);
        boolean sameAllDay = currentAllDay == parsed.allDay();
        if (sameTitle && sameStart && sameDue && sameAllDay) return false;

        java.time.Instant finalStart = newStart;
        java.time.Instant finalDue = newDue;
        boolean finalAllDay = parsed.allDay();
        runAs(userId, () -> {
            TaskModels.TaskItem current = tasks.get(publicId);
            TaskModels.TaskCommand command = new TaskModels.TaskCommand(
                    current.listId(), parsed.summary(), current.description(), current.priority(),
                    finalStart.toString(), finalDue.toString(), finalAllDay, current.timezone(),
                    null, current.parentId(), current.tagIds(),
                    current.checklist().stream().map(item -> new TaskModels.ChecklistCommand(
                            item.publicId(), item.title(), item.completed(), item.sortOrder())).toList(),
                    current.rrule(), current.recurrenceAnchor(), current.sortOrder(), current.itemKind());
            tasks.update(publicId, command, null);
        });
        return true;
    }

    private void runAs(long userId, Runnable action) {
        List<Object[]> users = jdbc.query("SELECT username,nickname FROM app_user WHERE id=?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)}, userId);
        String username = users.isEmpty() ? "caldav" : (String) users.get(0)[0];
        String nickname = users.isEmpty() ? "caldav" : (String) users.get(0)[1];
        CurrentUser user = new CurrentUser(userId, username, nickname, Set.of("task:read", "task:write"));
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                user.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private record Parsed(String summary, String dtStart, String dtEnd, boolean allDay) {
    }

    /** 只解析 VEVENT 的顶层属性；续行（以空格开头）先拼回去，否则长标题会被截断。 */
    private Parsed parse(String ics) {
        if (ics == null) return null;
        String unfolded = ics.replace("\r\n ", "").replace("\n ", "");
        String summary = null;
        String start = null;
        String end = null;
        boolean allDay = false;
        boolean inEvent = false;
        for (String line : unfolded.split("\\r?\\n")) {
            if (line.startsWith("BEGIN:VEVENT")) {
                inEvent = true;
                continue;
            }
            if (line.startsWith("END:VEVENT")) break;
            if (!inEvent) continue;
            if (line.startsWith("SUMMARY:")) summary = unescape(line.substring(8));
            else if (line.startsWith("DTSTART")) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    allDay = line.substring(0, idx).contains("VALUE=DATE");
                    start = line.substring(idx + 1).trim();
                }
            } else if (line.startsWith("DTEND")) {
                int idx = line.indexOf(':');
                if (idx > 0) end = line.substring(idx + 1).trim();
            }
        }
        if (start == null) return null;
        return new Parsed(summary, start, end, allDay);
    }

    private java.time.Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.endsWith("Z")) {
                return java.time.LocalDateTime.parse(value.substring(0, value.length() - 1),
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(java.time.ZoneOffset.UTC);
            }
            return LocalDate.parse(value, DATE).atStartOfDay(ZoneId.of("UTC")).toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    private String uidOf(String href) {
        if (href == null) return null;
        int slash = href.lastIndexOf('/');
        String name = slash >= 0 ? href.substring(slash + 1) : href;
        if (!name.endsWith(".ics")) return null;
        try {
            return java.net.URLDecoder.decode(name.substring(0, name.length() - 4), StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            return name.substring(0, name.length() - 4);
        }
    }

    // ---------------------------------------------------------------- CalDAV 调用

    private String put(String collection, Desired item) {
        String path = collection.endsWith("/") ? collection : collection + "/";
        String url = baseUrl + path + item.uid() + ".ics";
        String body = fold(item.ics());
        var response = client.put().uri(url)
                .header("Authorization", authorization())
                .header("Content-Type", "text/calendar; charset=utf-8")
                .body(body)
                .retrieve()
                .toBodilessEntity();
        String etag = response.getHeaders().getETag();
        return etag == null ? null : etag.replace("\"", "");
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
