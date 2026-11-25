package com.salarytracker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.integration.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨日任务的「今天」口径：只要今天落在任务的开始—截止区间里，今天就要能看到它，
 * 而不是等到截止那天才算今天到期。
 */
class TaskTodaySpanIntegrationTest extends MySqlIntegrationTestSupport {

    @Test
    void multiDayTaskShowsOnEveryDayItCovers() {
        long userId = createUser();
        TaskService tasks = service(userId);
        ZoneId zone = ZoneId.of(tasks.settings().timezone());
        LocalDate today = LocalDate.now(zone);
        var list = tasks.createList(new TaskModels.TaskListCommand("跨日", null, null, 1, false),
                "span-list-" + UUID.randomUUID());

        // 今天开始、两天后结束：今天、明天、未来 7 天都应命中
        var spanning = create(tasks, list.publicId(), "三天工期",
                today.atTime(9, 0), today.plusDays(2).atTime(18, 0));
        assertTrue(todayIds(tasks).contains(spanning.publicId()), "跨日任务今天应出现");
        assertTrue(viewIds(tasks, "TODAY").contains(spanning.publicId()), "今天视图应包含跨日任务");
        assertTrue(viewIds(tasks, "TOMORROW").contains(spanning.publicId()), "明天视图应包含覆盖明天的跨日任务");
        assertTrue(viewIds(tasks, "NEXT7").contains(spanning.publicId()), "未来 7 天应包含跨日任务");

        // 明天才开始的任务不能提前出现在今天
        var future = create(tasks, list.publicId(), "明天开工",
                today.plusDays(1).atTime(9, 0), today.plusDays(3).atTime(18, 0));
        assertFalse(todayIds(tasks).contains(future.publicId()), "明天开始的任务不应出现在今天");
        assertTrue(viewIds(tasks, "TOMORROW").contains(future.publicId()));

        // 已经结束的跨日任务只算逾期，不能混进今天
        var ended = create(tasks, list.publicId(), "昨天收尾",
                today.minusDays(3).atTime(9, 0), today.minusDays(1).atTime(18, 0));
        assertFalse(todayIds(tasks).contains(ended.publicId()), "已结束的任务不算今天");
        assertTrue(tasks.overdueTasks(50).stream().anyMatch(item -> item.publicId().equals(ended.publicId())));
    }

    @Test
    void dateRangeQueryUsesOverlapInsteadOfDueDateOnly() {
        long userId = createUser();
        TaskService tasks = service(userId);
        ZoneId zone = ZoneId.of(tasks.settings().timezone());
        LocalDate today = LocalDate.now(zone);
        var list = tasks.createList(new TaskModels.TaskListCommand("范围", null, null, 1, false),
                "range-list-" + UUID.randomUUID());
        // 上周一开始、下周才结束的长任务：查本周要有它，尽管截止日不落在本周
        var longTask = create(tasks, list.publicId(), "长线工程",
                today.minusDays(7).atTime(9, 0), today.plusDays(7).atTime(18, 0));

        java.time.Instant from = today.minusDays(1).atStartOfDay(zone).toInstant();
        java.time.Instant to = today.plusDays(2).atStartOfDay(zone).toInstant();
        boolean hit = tasks.listTasks(null, null, null, null, null, from, to, null, 0, 50)
                .items().stream().anyMatch(item -> item.publicId().equals(longTask.publicId()));
        assertTrue(hit, "日期范围查询按交集判定，跨日任务应命中");
    }

    private TaskModels.TaskItem create(TaskService tasks, String listId, String title,
                                       LocalDateTime start, LocalDateTime end) {
        ZoneId zone = ZoneId.of(tasks.settings().timezone());
        return tasks.create(new TaskModels.TaskCommand(listId, title, "", "NONE",
                start.atZone(zone).toInstant().toString(), end.atZone(zone).toInstant().toString(),
                false, zone.getId(), null), "create-" + UUID.randomUUID());
    }

    private List<String> todayIds(TaskService tasks) {
        return tasks.todayTasks(50).stream().map(TaskModels.TaskItem::publicId).toList();
    }

    private List<String> viewIds(TaskService tasks, String view) {
        return tasks.listTasks(null, null, view, null, 0, 200).items().stream()
                .map(TaskModels.TaskItem::publicId).toList();
    }

    private TaskService service(long userId) {
        CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
        when(currentUser.id()).thenReturn(userId);
        return new TaskService(jdbc, currentUser, new TaskChangeLog(jdbc, new ObjectMapper().findAndRegisterModules()));
    }

    private long createUser() {
        String username = "task-span-" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,nickname) VALUES(?, '!', '跨日测试')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
