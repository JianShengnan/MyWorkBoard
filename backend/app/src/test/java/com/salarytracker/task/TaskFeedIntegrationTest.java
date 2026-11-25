package com.salarytracker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.push.PushService;
import com.salarytracker.integration.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskFeedIntegrationTest extends MySqlIntegrationTestSupport {
    @Test
    void feedStoresOnlyHashAndRotationRevokesOldTokenImmediately() {
        long userId = createUser();
        CurrentUserResolver resolver = mock(CurrentUserResolver.class);
        when(resolver.id()).thenReturn(userId);
        TaskService tasks = new TaskService(jdbc, resolver, new TaskChangeLog(jdbc, new ObjectMapper().findAndRegisterModules()));
        var list = tasks.createList(new TaskModels.TaskListCommand("订阅", null, null, 1, false), "feed-list");
        var task = tasks.create(new TaskModels.TaskCommand(list.publicId(), "公开标题", "私密描述", "HIGH", null,
                Instant.now().plusSeconds(3600).toString(), false, "Asia/Shanghai", null), "feed-task");
        TaskReminderService reminders = new TaskReminderService(jdbc, resolver, new TaskInboxStream(), mock(PushService.class));
        reminders.create(task.publicId(), new TaskModels.ReminderCommand("RELATIVE", 30, null, "IN_APP", false));
        TaskFeedService feeds = new TaskFeedService(jdbc, resolver);

        var created = feeds.create("https://work.example");
        assertTrue(created.feedUrl().startsWith("https://work.example/api/v1/tasks/feed/"));
        String raw = created.feedUrl().substring(created.feedUrl().lastIndexOf('/') + 1).replace(".ics", "");
        assertFalse(jdbc.queryForObject("SELECT token_hash FROM task_feed_token WHERE user_id=? AND revoked_at IS NULL", String.class, userId).contains(raw));
        String calendar = feeds.feed(raw, "127.0.0.1");
        assertTrue(calendar.contains("SUMMARY:公开标题"));
        assertTrue(calendar.contains("BEGIN:VALARM"));
        // 清单与优先级要能在日历里看出来，否则只有一个标题，订阅等于没用
        assertTrue(calendar.contains("CATEGORIES:订阅"));
        assertTrue(calendar.contains("优先级：高"));
        assertFalse(calendar.contains("DUE:"), "VEVENT 里不应出现 VTODO 的 DUE 属性");
        assertFalse(calendar.contains("私密描述"));

        var rotated = feeds.rotate("https://work.example");
        assertThrows(RuntimeException.class, () -> feeds.feed(raw, "127.0.0.1"));
        String next = rotated.feedUrl().substring(rotated.feedUrl().lastIndexOf('/') + 1).replace(".ics", "");
        assertTrue(feeds.feed(next, "127.0.0.1").contains("公开标题"));
        assertNotNull(feeds.status().lastAccessedAt());
        feeds.revoke();
        assertThrows(RuntimeException.class, () -> feeds.feed(next, "127.0.0.1"));
    }

    @Test
    void feedAlsoExportsCountdownsHabitsAndWorktime() {
        long userId = createUser();
        CurrentUserResolver resolver = mock(CurrentUserResolver.class);
        when(resolver.id()).thenReturn(userId);
        TaskService tasks = new TaskService(jdbc, resolver, new TaskChangeLog(jdbc, new ObjectMapper().findAndRegisterModules()));
        tasks.createList(new TaskModels.TaskListCommand("订阅", null, null, 1, false), "feed-mixed");
        String countdown = UUID.randomUUID().toString();
        String habit = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO countdown(public_id,user_id,title,target_date,kind,repeat_rule,note,archived,precise_time) " +
                        "VALUES(?,?,?,?,?,?,?,?,?)", countdown, userId, "妈妈生日", java.sql.Date.valueOf("2026-12-25"),
                "BIRTHDAY", "YEARLY", "记得订蛋糕", 0, "");
        jdbc.update("INSERT INTO habit(public_id,user_id,name,frequency,custom_days,remind_at,start_date) " +
                        "VALUES(?,?,?,?,?,?,?)", habit, userId, "晨跑", "CUSTOM", "1,3,5", "07:30",
                java.sql.Date.valueOf("2026-11-01"));
        jdbc.update("INSERT INTO work_record(user_id,date,start_time,end_time,rest_min,overtime_min) VALUES(?,?,?,?,?,?)",
                userId, java.sql.Date.valueOf("2026-11-24"), java.sql.Time.valueOf("09:00:00"),
                java.sql.Time.valueOf("18:30:00"), 90, 30);
        TaskFeedService feeds = new TaskFeedService(jdbc, resolver);
        String raw = feeds.create("https://work.example").feedUrl();
        raw = raw.substring(raw.lastIndexOf('/') + 1).replace(".ics", "");

        String calendar = feeds.feed(raw, "127.0.0.1");
        // 倒数日：全天 + 每年重复
        assertTrue(calendar.contains("UID:" + countdown + "@countdown"));
        assertTrue(calendar.contains("RRULE:FREQ=YEARLY"));
        assertTrue(calendar.contains("SUMMARY:妈妈生日"));
        assertTrue(calendar.contains("DTSTART;VALUE=DATE:20261225"));
        assertTrue(calendar.contains("记得订蛋糕"));
        // 习惯：自定义星期 → BYDAY，带提醒时间的写成时刻
        assertTrue(calendar.contains("UID:" + habit + "@habit"));
        assertTrue(calendar.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR"));
        assertTrue(calendar.contains("SUMMARY:晨跑"));
        // 工时：上班到下班作为一个时段，并标为忙碌
        assertTrue(calendar.contains("UID:work-"));
        assertTrue(calendar.contains("SUMMARY:上班 09:00"));
        assertTrue(calendar.contains("CATEGORIES:工时"));
        assertTrue(calendar.contains("TRANSP:OPAQUE"));
        assertFalse(calendar.contains("VTODO"));
    }

    private long createUser() {
        String username = "feed-" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,nickname) VALUES(?, '!', '订阅测试')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
