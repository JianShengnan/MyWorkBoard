package com.salarytracker.caldav;

import com.salarytracker.integration.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CalDAV 事件组装：全天事件必须用「不含的次日」作为 DTEND（iCal 语义，与飞书 API 相反），
 * 单日任务不能跨两天；指纹只随内容变化。
 */
class CalDavEventsTest extends MySqlIntegrationTestSupport {
    private final CalDavService service = new CalDavService(jdbc, "http://127.0.0.1:1", "u", "p");

    @Test
    void allDayEventUsesExclusiveEndDate() {
        long userId = createUser();
        long listId = createList(userId);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);

        String span = createTask(userId, listId, "出差", today.minusDays(1).atStartOfDay(),
                today.plusDays(2).atStartOfDay(), true);
        String single = createTask(userId, listId, "例会", today.atStartOfDay(), today.atStartOfDay(), true);

        List<CalDavService.Desired> events = service.taskEvents(userId, zone);
        assertEquals(2, events.size());
        var byUid = events.stream().collect(java.util.stream.Collectors.toMap(CalDavService.Desired::uid, e -> e));

        String spanIcs = byUid.get("task-" + span + "@workboard").ics();
        assertTrue(spanIcs.contains("DTSTART;VALUE=DATE:" + ymd(today.minusDays(1))), spanIcs);
        assertTrue(spanIcs.contains("DTEND;VALUE=DATE:" + ymd(today.plusDays(3))),
                "iCal 的 DTEND 是「不含的次日」，结束日要 +1：" + spanIcs);

        String singleIcs = byUid.get("task-" + single + "@workboard").ics();
        assertTrue(singleIcs.contains("DTSTART;VALUE=DATE:" + ymd(today)), singleIcs);
        assertTrue(singleIcs.contains("DTEND;VALUE=DATE:" + ymd(today.plusDays(1))),
                "单日全天任务只能占一天，DTEND 必须是次日：" + singleIcs);
    }

    @Test
    void fingerprintChangesOnlyWhenContentChanges() {
        long userId = createUser();
        long listId = createList(userId);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        String publicId = createTask(userId, listId, "写周报",
                LocalDate.now(zone).atTime(9, 0), LocalDate.now(zone).atTime(10, 0), false);

        String before = service.taskEvents(userId, zone).get(0).fingerprint();
        assertEquals(before, service.taskEvents(userId, zone).get(0).fingerprint(), "内容没变指纹必须稳定");

        jdbc.update("UPDATE task SET title='写周报（改）' WHERE public_id=?", publicId);
        assertFalse(before.equals(service.taskEvents(userId, zone).get(0).fingerprint()),
                "改标题后指纹必须变化，否则客户端永远看不到更新");
    }

    @Test
    void escapesAndFoldsIcalText() {
        assertEquals("a\\;b\\,c\\nd", CalDavService.escape("a;b,c\nd"));

        StringBuilder longLine = new StringBuilder("DESCRIPTION:");
        longLine.append("中文描述".repeat(30));
        String folded = CalDavService.fold("BEGIN:VCALENDAR\r\n" + longLine + "\r\nEND:VCALENDAR\r\n");
        for (String line : folded.split("\r\n")) {
            int bytes = line.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= 76, "折行后每行不得超过 76 字节，实际 " + bytes + "：" + line);
        }
        assertTrue(folded.contains("\r\n "), "续行必须以空格开头");
        assertTrue(folded.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(folded.endsWith("END:VCALENDAR\r\n"));
    }

    private static String ymd(LocalDate date) {
        return String.format("%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    private long createUser() {
        String username = "caldav-" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,nickname) VALUES(?, '!', 'CalDAV')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private long createList(long userId) {
        String publicId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO task_list(public_id,user_id,name,sort_order,archived,is_pinned,view_type,object_type," +
                        "show_in_smart_lists) VALUES(?,?,'同步',1,FALSE,FALSE,'LIST','TASK',TRUE)",
                publicId, userId);
        return jdbc.queryForObject("SELECT id FROM task_list WHERE public_id=?", Long.class, publicId);
    }

    private String createTask(long userId, long listId, String title,
                              java.time.LocalDateTime start, java.time.LocalDateTime end, boolean allDay) {
        String publicId = UUID.randomUUID().toString();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        jdbc.update("INSERT INTO task(public_id,user_id,list_id,title,description,status,priority,start_at,due_at," +
                        "all_day,timezone,revision,sort_order,deleted,item_kind,created_by,client_op_key) " +
                        "VALUES(?,?,?,?,'','OPEN','NONE',?,?,?,'Asia/Shanghai',0,0,FALSE,'TASK',?,?)",
                publicId, userId, listId, title,
                start == null ? null : java.sql.Timestamp.from(start.atZone(zone).toInstant()),
                java.sql.Timestamp.from(end.atZone(zone).toInstant()), allDay, userId, UUID.randomUUID().toString());
        return publicId;
    }
}
