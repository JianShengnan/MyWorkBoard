package com.salarytracker.feishu;

import com.salarytracker.integration.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 飞书日历的「期望事件」组装：全天与跨日任务要变成一条覆盖整段的日程，
 * 而不是每天都在截止日堆一个点；窗口外的任务不参与同步。
 */
class FeishuCalendarEventsTest extends MySqlIntegrationTestSupport {
    private final FeishuCalendarService service = new FeishuCalendarService(jdbc, mock(FeishuClient.class),
            new FeishuConfig("app", "secret", "", ""));

    @Test
    void tasksBecomeSpanningOrTimedEvents() {
        long userId = createUser();
        long listId = createList(userId);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);

        String allDay = createTask(userId, listId, "出差", today.minusDays(1).atStartOfDay(),
                today.plusDays(2).atStartOfDay(), true);
        String timed = createTask(userId, listId, "上线值守", today.atTime(9, 0),
                today.plusDays(1).atTime(18, 0), false);
        // 60 天后才开始，落在同步窗口外
        createTask(userId, listId, "太远了", today.plusDays(60).atTime(9, 0),
                today.plusDays(60).atTime(10, 0), false);

        List<FeishuCalendarService.Desired> events = service.taskEvents(userId, zone);
        assertEquals(2, events.size(), "窗口外的任务不应参与同步");

        var byKey = events.stream().collect(Collectors.toMap(FeishuCalendarService.Desired::key, item -> item));
        var span = byKey.get("task:" + allDay);
        assertNotNull(span);
        assertTrue(span.allDay(), "全天跨日任务应写成全天日程");
        assertEquals(today.minusDays(1), span.startDate());
        assertEquals(today.plusDays(3), span.endDate(), "全天事件的结束日是「不含」的次日");

        var block = byKey.get("task:" + timed);
        assertNotNull(block);
        assertFalse(block.allDay(), "带时刻的跨日任务应保留时间");
        assertNotNull(block.startAt());
        assertNotNull(block.endAt());
    }

    @Test
    void fingerprintChangesOnlyWhenContentChanges() {
        long userId = createUser();
        long listId = createList(userId);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        String publicId = createTask(userId, listId, "写周报", today.atTime(9, 0), today.atTime(10, 0), false);

        String before = service.taskEvents(userId, zone).get(0).fingerprint();
        assertEquals(before, service.taskEvents(userId, zone).get(0).fingerprint(), "内容没变指纹必须稳定");

        jdbc.update("UPDATE task SET title='写周报（改）' WHERE public_id=?", publicId);
        String after = service.taskEvents(userId, zone).get(0).fingerprint();
        assertFalse(before.equals(after), "改标题后指纹必须变化，否则飞书那边永远不会更新");
    }

    private long createUser() {
        String username = "feishu-cal-" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,nickname) VALUES(?, '!', '飞书日历')", username);
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
                              LocalDateTime start, LocalDateTime end, boolean allDay) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        String publicId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO task(public_id,user_id,list_id,title,description,status,priority,start_at,due_at," +
                        "all_day,timezone,revision,sort_order,deleted,item_kind,created_by,client_op_key) " +
                        "VALUES(?,?,?,?,'','OPEN','NONE',?,?,?,'Asia/Shanghai',0,0,FALSE,'TASK',?,?)",
                publicId, userId, listId, title,
                Timestamp.from(start.atZone(zone).toInstant()),
                Timestamp.from(end.atZone(zone).toInstant()), allDay, userId, publicId);
        return publicId;
    }
}
