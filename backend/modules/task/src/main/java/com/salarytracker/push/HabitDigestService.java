package com.salarytracker.push;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 习惯未打卡汇总：每小时整点后 5 分扫一次，命中用户设定的整点时发一条「今天还差哪几个」。
 *
 * <p>只统计「今天有排期」且还没打卡的习惯：
 * DAILY / CUSTOM / INTERVAL 按排期算；WEEKLY_N / MONTHLY_N 是周期目标制，不能按天催，直接跳过；
 * 设为不在今日显示（show_in_today=false）的习惯也不催。</p>
 */
@Service
public class HabitDigestService {
    private static final Logger log = LoggerFactory.getLogger(HabitDigestService.class);

    private final JdbcTemplate jdbc;
    private final PushService push;

    public HabitDigestService(JdbcTemplate jdbc, PushService push) {
        this.jdbc = jdbc;
        this.push = push;
    }

    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "habitPushDigest", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void scan() {
        List<UserRow> users = jdbc.query("SELECT u.id,u.timezone,p.habit_digest,p.habit_digest_hour,p.habit_last_sent_on " +
                        "FROM app_user u LEFT JOIN push_preference p ON p.user_id=u.id WHERE u.status='ACTIVE'",
                (row, number) -> {
                    Date lastSent = row.getDate("habit_last_sent_on");
                    Object digest = row.getObject("habit_digest");
                    Object hour = row.getObject("habit_digest_hour");
                    return new UserRow(row.getLong("id"), row.getString("timezone"),
                            digest == null ? null : row.getBoolean("habit_digest"),
                            hour == null ? null : row.getInt("habit_digest_hour"),
                            lastSent == null ? null : lastSent.toLocalDate());
                });
        for (UserRow user : users) {
            try {
                digest(user);
            } catch (Exception exception) {
                log.warn("习惯提醒生成失败 userId={}: {}", user.id(), exception.getMessage());
            }
        }
    }

    private void digest(UserRow user) {
        if (Boolean.FALSE.equals(user.habitDigest())) return;
        int hour = user.habitDigestHour() == null ? 21 : user.habitDigestHour();
        ZoneId zone = zone(user.timezone());
        LocalDate today = LocalDate.now(zone);
        if (today.equals(user.lastSentOn())) return;
        int nowHour = LocalTime.now(zone).getHour();
        // 允许延迟 2 小时（服务在整点后重启也能补发），超过就不再打扰
        if (nowHour < hour || nowHour > Math.min(23, hour + 2)) return;

        List<HabitRow> habits = jdbc.query("SELECT h.name,h.frequency,h.custom_days,h.start_date,h.interval_days," +
                        "(SELECT c.status FROM habit_checkin c WHERE c.habit_id=h.id AND c.checkin_date=? " +
                        " ORDER BY c.id DESC LIMIT 1) AS checkin_status " +
                        "FROM habit h WHERE h.user_id=? AND h.is_archived=FALSE AND h.deleted_at IS NULL " +
                        "AND h.frequency IN ('DAILY','CUSTOM','INTERVAL') AND h.show_in_today=TRUE ORDER BY h.sort_order,h.id",
                (row, number) -> new HabitRow(row.getString("name"), row.getString("frequency"),
                        row.getString("custom_days"), row.getDate("start_date").toLocalDate(),
                        row.getInt("interval_days"), row.getString("checkin_status")),
                Date.valueOf(today), user.id());

        List<String> missing = new ArrayList<>();
        for (HabitRow habit : habits) {
            if (!scheduled(habit, today)) continue;
            String status = habit.checkinStatus();
            if ("DONE".equals(status) || "SKIP".equals(status)) continue;
            missing.add(habit.name());
        }
        // 先标记再发送：宁可漏发一次，也不要每小时间隔重复轰炸
        markSent(user.id(), today);
        if (missing.isEmpty()) return;
        String names = missing.size() <= 5 ? String.join("、", missing)
                : String.join("、", missing.subList(0, 5)) + " 等 " + missing.size() + " 个";
        push.dispatch(user.id(), "HABIT_DIGEST", "今天还有 " + missing.size() + " 个习惯没打卡", names, "/tasks/habits");
    }

    private boolean scheduled(HabitRow habit, LocalDate day) {
        LocalDate start = habit.startDate();
        if (day.isBefore(start)) return false;
        if ("CUSTOM".equals(habit.frequency())) return habit.customDays().contains(day.getDayOfWeek().getValue());
        if ("INTERVAL".equals(habit.frequency())) {
            long gap = ChronoUnit.DAYS.between(start, day);
            return gap % Math.max(1, habit.intervalDays()) == 0;
        }
        return true;
    }

    private void markSent(long userId, LocalDate day) {
        jdbc.update("INSERT INTO push_preference(user_id,habit_last_sent_on) VALUES(?,?) " +
                "ON DUPLICATE KEY UPDATE habit_last_sent_on=VALUES(habit_last_sent_on)", userId, Date.valueOf(day));
    }

    private ZoneId zone(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        } catch (Exception exception) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private record UserRow(long id, String timezone, Boolean habitDigest, Integer habitDigestHour, LocalDate lastSentOn) {
    }

    private record HabitRow(String name, String frequency, String customDaysRaw, LocalDate startDate,
                            int intervalDays, String checkinStatus) {
        Set<Integer> customDays() {
            if (customDaysRaw == null || customDaysRaw.isBlank()) return Set.of();
            return Arrays.stream(customDaysRaw.split(","))
                    .map(String::trim)
                    .filter(part -> part.matches("\\d+"))
                    .map(Integer::valueOf)
                    .collect(Collectors.toSet());
        }
    }
}
