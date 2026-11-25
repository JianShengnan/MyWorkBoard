package com.salarytracker.push;

import com.salarytracker.integration.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 习惯未打卡汇总的取数口径：只催今天有排期、且还没打卡的习惯；同一天只发一次。
 */
class HabitDigestPushIntegrationTest extends MySqlIntegrationTestSupport {

    private PushService push;
    private HabitDigestService service;
    private long userId;

    @BeforeEach
    void setUp() {
        // 容器里可能还有别的测试类留下的用户；先把所有人的汇总关掉，只让本次用户参与扫描，
        // 否则 scan() 会遍历全部活跃用户，把别人的习惯也算进来。
        String username = "habit-qa-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO app_user(username,password_hash,nickname,timezone,status) VALUES(?,?,?,?,?)",
                username, "x", "习惯验收", "Asia/Shanghai", "ACTIVE");
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);

        jdbc.update("UPDATE push_preference SET habit_digest=FALSE");
        jdbc.update("INSERT IGNORE INTO push_preference(user_id,habit_digest) SELECT id,FALSE FROM app_user");

        int hour = LocalTime.now(ZoneId.of("Asia/Shanghai")).getHour();
        jdbc.update("UPDATE push_preference SET habit_digest=TRUE,habit_digest_hour=?,habit_last_sent_on=NULL WHERE user_id=?",
                hour, userId);

        push = mock(PushService.class);
        service = new HabitDigestService(jdbc, push);
    }

    @Test
    void onlyNagsAboutHabitsScheduledTodayThatAreStillOpen() {
        habit("晨跑", "DAILY", null, 1);
        habit("周报", "WEEKLY_N", null, 1);
        long done = habit("喝水", "DAILY", null, 1);
        checkin(done, "DONE");
        long skipped = habit("冥想", "DAILY", null, 1);
        checkin(skipped, "SKIP");
        habit("归档的习惯", "DAILY", null, 1, true);

        service.scan();

        verify(push, times(1)).dispatch(eq(userId), eq("HABIT_DIGEST"), argThat((PushModels.Message message) ->
                message.title().contains("1 个习惯") && message.lines().stream().anyMatch(line -> line.contains("晨跑"))));
        verify(push, never()).dispatch(anyLong(), eq("HABIT_DIGEST"),
                argThat((PushModels.Message message) -> message.lines().stream().anyMatch(line -> line.contains("周报"))));
        verify(push, never()).dispatch(anyLong(), eq("HABIT_DIGEST"),
                argThat((PushModels.Message message) -> message.lines().stream().anyMatch(line -> line.contains("喝水"))));
        verify(push, never()).dispatch(anyLong(), eq("HABIT_DIGEST"),
                argThat((PushModels.Message message) -> message.lines().stream().anyMatch(line -> line.contains("冥想"))));
        verify(push, never()).dispatch(anyLong(), eq("HABIT_DIGEST"),
                argThat((PushModels.Message message) -> message.lines().stream().anyMatch(line -> line.contains("归档"))));
    }

    @Test
    void skipsWhenEveryScheduledHabitIsAlreadyCheckedIn() {
        long done = habit("晨跑", "DAILY", null, 1);
        checkin(done, "DONE");

        service.scan();

        verify(push, never()).dispatch(anyLong(), anyString(), any(PushModels.Message.class));
    }

    @Test
    void digestIsSentAtMostOncePerDay() {
        habit("晨跑", "DAILY", null, 1);

        service.scan();
        service.scan();

        verify(push, times(1)).dispatch(anyLong(), eq("HABIT_DIGEST"), any(PushModels.Message.class));
    }

    @Test
    void digestStaysSilentWhenTheUserTurnedItOff() {
        jdbc.update("UPDATE push_preference SET habit_digest=FALSE WHERE user_id=?", userId);
        habit("晨跑", "DAILY", null, 1);

        service.scan();

        verify(push, never()).dispatch(anyLong(), anyString(), any(PushModels.Message.class));
    }

    @Test
    void customFrequencyOnlyCountsItsWeekdays() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        int weekday = today.getDayOfWeek().getValue();
        int other = weekday == 7 ? 1 : weekday + 1;
        habit("只在别的日子做", "CUSTOM", String.valueOf(other), 1);

        service.scan();

        verify(push, never()).dispatch(anyLong(), anyString(), any(PushModels.Message.class));
    }

    private long habit(String name, String frequency, String customDays, int showInToday) {
        return habit(name, frequency, customDays, showInToday, false);
    }

    private long habit(String name, String frequency, String customDays, int showInToday, boolean archived) {
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(7);
        jdbc.update("INSERT INTO habit(public_id,user_id,name,frequency,custom_days,start_date,show_in_today,is_archived) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), userId, name, frequency, customDays, start, showInToday == 1, archived);
        return jdbc.queryForObject("SELECT id FROM habit WHERE user_id=? AND name=?", Long.class, userId, name);
    }

    private void checkin(long habitId, String status) {
        jdbc.update("INSERT INTO habit_checkin(public_id,habit_id,user_id,checkin_date,count,status) VALUES(?,?,?,?,1,?)",
                UUID.randomUUID().toString(), habitId, userId,
                LocalDate.now(ZoneId.of("Asia/Shanghai")), status);
    }
}
