package com.salarytracker.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周报聚合：一次把一周的任务、账目、工时、习惯算齐，交给前端画图。
 *
 * <p>放在服务端而不是前端拼，是因为这些口径（净工时怎么算、转账要不要排除、逾期按哪一天）
 * 已经在周报、日历订阅、飞书同步里出现过多次，集中在一处才好保持一致。</p>
 */
@Service
public class WeeklyReportService {
    private final JdbcTemplate jdbc;

    public WeeklyReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Kpi(int completed, int created, int overdue, int open,
                      double expense, double income, int expenseCount,
                      double netHours, int workDays, int overtimeMinutes,
                      int habitDone, int habitMiss, double habitRate) {
    }

    /** 通用「名称 + 数值 + 笔数」，用于分类分布。 */
    public record Bucket(String label, double value, int count) {
    }

    /** 通用「日期 + 数值」，前端负责补齐空缺日期。 */
    public record DayPoint(String date, double value) {
    }

    public record WeeklyReport(String weekStart, String weekEnd, boolean partial,
                               Kpi kpi,
                               List<Bucket> taskLists, List<DayPoint> taskDaily,
                               List<Bucket> expenseCategories, List<DayPoint> expenseDaily,
                               List<DayPoint> workDaily, List<Bucket> workDays,
                               List<Bucket> habitStats) {
    }

    public WeeklyReport weekly(long userId, LocalDate anyDay) {
        LocalDate start = anyDay.with(DayOfWeek.MONDAY);
        LocalDate end = start.plusDays(6);
        LocalDate today = LocalDate.now(zone(userId));
        boolean partial = !today.isAfter(end);
        // 本周未结束时，右边界收到「明天零点」，避免把未来算进来
        LocalDate effectiveEnd = partial ? today.plusDays(1) : end.plusDays(1);

        Timestamp from = Timestamp.valueOf(start.atStartOfDay());
        Timestamp to = Timestamp.valueOf(effectiveEnd.atStartOfDay());
        Date fromDate = Date.valueOf(start);
        Date toDate = Date.valueOf(effectiveEnd.minusDays(1));

        Map<String, Object> taskCounts = jdbc.queryForMap(
                "SELECT " +
                        "COALESCE(SUM(status='COMPLETED' AND completed_at>=? AND completed_at<?),0) completed," +
                        "COALESCE(SUM(created_at>=? AND created_at<?),0) created," +
                        "COALESCE(SUM(status='OPEN'),0) open_cnt," +
                        "COALESCE(SUM(status='OPEN' AND due_at IS NOT NULL AND due_at<?),0) overdue " +
                        "FROM task WHERE user_id=? AND deleted=FALSE",
                from, to, from, to, to, userId);

        int completed = number(taskCounts.get("completed"));
        int created = number(taskCounts.get("created"));
        int open = number(taskCounts.get("open_cnt"));
        int overdue = number(taskCounts.get("overdue"));

        List<Bucket> taskLists = jdbc.query(
                "SELECT l.name, COUNT(*) c FROM task t JOIN task_list l ON l.id=t.list_id " +
                        "WHERE t.user_id=? AND t.deleted=FALSE AND t.status='COMPLETED' " +
                        "AND t.completed_at>=? AND t.completed_at<? GROUP BY l.name ORDER BY c DESC LIMIT 8",
                (rs, i) -> new Bucket(rs.getString(1), rs.getInt(2), rs.getInt(2)),
                userId, from, to);

        List<DayPoint> taskDaily = jdbc.query(
                "SELECT DATE(completed_at) d, COUNT(*) c FROM task " +
                        "WHERE user_id=? AND deleted=FALSE AND status='COMPLETED' " +
                        "AND completed_at>=? AND completed_at<? GROUP BY DATE(completed_at) ORDER BY d",
                (rs, i) -> new DayPoint(rs.getDate(1).toString(), rs.getInt(2)),
                userId, from, to);

        double expense = 0;
        double income = 0;
        int expenseCount = 0;
        try {
            List<Object[]> sums = jdbc.query(
                    "SELECT kind, COALESCE(SUM(amount),0) amt, COUNT(*) c FROM ledger_transaction " +
                            "WHERE user_id=? AND occurred_on BETWEEN ? AND ? AND kind IN ('EXPENSE','INCOME') " +
                            "GROUP BY kind",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getBigDecimal(2), rs.getInt(3)},
                    userId, fromDate, toDate);
            for (Object[] row : sums) {
                double amount = decimal(row[1]);
                if ("EXPENSE".equals(row[0])) {
                    expense += amount;
                    expenseCount = (Integer) row[2];
                } else {
                    income += amount;
                }
            }
        } catch (RuntimeException ignored) {
            // 账本表在某些部署里可能尚未初始化，周报退化到只显示任务与工时
        }

        List<Bucket> expenseCategories = new ArrayList<>();
        List<DayPoint> expenseDaily = new ArrayList<>();
        try {
            expenseCategories = jdbc.query(
                    "SELECT COALESCE(c.name,'未分类') n, SUM(x.amount) amt, COUNT(*) c " +
                            "FROM ledger_transaction x LEFT JOIN ledger_category c ON c.id=x.category_id " +
                            "WHERE x.user_id=? AND x.kind='EXPENSE' AND x.occurred_on BETWEEN ? AND ? " +
                            "GROUP BY n ORDER BY amt DESC LIMIT 8",
                    (rs, i) -> new Bucket(rs.getString(1), decimal(rs.getBigDecimal(2)), rs.getInt(3)),
                    userId, fromDate, toDate);
            expenseDaily = jdbc.query(
                    "SELECT occurred_on d, SUM(amount) amt FROM ledger_transaction " +
                            "WHERE user_id=? AND kind='EXPENSE' AND occurred_on BETWEEN ? AND ? " +
                            "GROUP BY occurred_on ORDER BY d",
                    (rs, i) -> new DayPoint(rs.getDate(1).toString(), decimal(rs.getBigDecimal(2))),
                    userId, fromDate, toDate);
        } catch (RuntimeException ignored) {
            // 同上
        }

        double netMinutes = 0;
        int workDays = 0;
        int overtimeMinutes = 0;
        List<DayPoint> workDaily = new ArrayList<>();
        try {
            List<Object[]> records = jdbc.query(
                    "SELECT date d, TIMESTAMPDIFF(MINUTE,start_time,end_time)" +
                            "-COALESCE(lunch_min,0)-COALESCE(rest_min,0) net, COALESCE(overtime_min,0) ot " +
                            "FROM work_record WHERE user_id=? AND deleted=FALSE AND date BETWEEN ? AND ? ORDER BY d",
                    (rs, i) -> new Object[]{rs.getDate(1).toString(), rs.getInt(2), rs.getInt(3)},
                    userId, fromDate, toDate);
            for (Object[] row : records) {
                int minutes = Math.max(0, (Integer) row[1]);
                netMinutes += minutes;
                overtimeMinutes += (Integer) row[2];
                workDays += 1;
                workDaily.add(new DayPoint((String) row[0], round(minutes / 60.0)));
            }
        } catch (RuntimeException ignored) {
            // 工时表缺失时保持 0
        }

        int habitDone = 0;
        int habitMiss = 0;
        List<Bucket> habitStats;
        try {
            List<Object[]> rows = jdbc.query(
                    "SELECT h.name, c.status, COUNT(*) c FROM habit_checkin c JOIN habit h ON h.id=c.habit_id " +
                            "WHERE c.user_id=? AND c.checkin_date BETWEEN ? AND ? GROUP BY h.name, c.status",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)},
                    userId, fromDate, toDate);
            Map<String, int[]> byHabit = new LinkedHashMap<>();
            for (Object[] row : rows) {
                String status = (String) row[1];
                int count = (Integer) row[2];
                if ("DONE".equals(status)) habitDone += count;
                else if ("MISS".equals(status)) habitMiss += count;
                int[] slot = byHabit.computeIfAbsent((String) row[0], key -> new int[]{0, 0});
                if ("DONE".equals(status)) slot[0] += count;
                else if ("MISS".equals(status)) slot[1] += count;
            }
            habitStats = byHabit.entrySet().stream()
                    .map(entry -> new Bucket(entry.getKey(), entry.getValue()[0],
                            entry.getValue()[0] + entry.getValue()[1]))
                    .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                    .limit(8)
                    .toList();
        } catch (RuntimeException exception) {
            habitStats = List.of();
        }
        int habitTotal = habitDone + habitMiss;

        Kpi kpi = new Kpi(completed, created, overdue, open,
                round(expense), round(income), expenseCount,
                round(netMinutes / 60.0), workDays, overtimeMinutes,
                habitDone, habitMiss, habitTotal == 0 ? 0 : round(habitDone * 100.0 / habitTotal));

        return new WeeklyReport(start.toString(), end.toString(), partial, kpi,
                taskLists, taskDaily, expenseCategories, expenseDaily, workDaily,
                List.of(), habitStats);
    }

    private ZoneId zone(long userId) {
        List<String> rows = jdbc.query("SELECT timezone FROM task_setting WHERE user_id=?",
                (rs, i) -> rs.getString(1), userId);
        String value = rows.isEmpty() ? null : rows.get(0);
        if (value == null || value.isBlank()) return ZoneId.of("Asia/Shanghai");
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        return 0;
    }

    private static double decimal(Object value) {
        if (value instanceof BigDecimal amount) return amount.doubleValue();
        if (value instanceof Number number) return number.doubleValue();
        return 0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 保留给后续「按天对比上周」的扩展点，避免调用方改签名。 */
    @SuppressWarnings("unused")
    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
