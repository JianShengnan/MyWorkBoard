package com.salarytracker.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周报 / 月报聚合。
 *
 * <p>口径集中在这里：净工时扣午休与休息、转账不计入收支、逾期按截止时间、跨日任务按区间计入。
 * 这些规则在日历订阅、飞书同步、推送里都出现过，散着写迟早对不上。</p>
 *
 * <p>明细条目统一成 {@link Item}，前端只负责列表渲染与跳转，不再关心业务差异。</p>
 */
@Service
public class ReportService {
    /** 明细条数上限：够看清发生了什么，又不至于把页面撑成流水账。 */
    private static final int ITEM_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ReportService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public enum PeriodType {
        WEEK, MONTH;

        static PeriodType parse(String value) {
            if (value == null) return WEEK;
            return "MONTH".equalsIgnoreCase(value) ? MONTH : WEEK;
        }
    }

    public record Kpi(int completed, int created, int overdue, int open,
                      double expense, double income, int expenseCount,
                      double netHours, int workDays, int overtimeMinutes,
                      int habitDone, int habitMiss, double habitRate,
                      int focusSessions, double focusHours) {
    }

    public record Bucket(String label, double value, int count) {
    }

    public record DayPoint(String date, double value) {
    }

    /** 明细条目：标题 + 右侧次要信息 + 备注 + 站内跳转。 */
    public record Item(String title, String meta, String note, String link) {
    }

    public record Report(String periodType, String start, String end, boolean partial,
                         String label,
                         Kpi kpi, Kpi previous,
                         List<Bucket> taskLists, List<DayPoint> taskDaily, List<Item> taskItems,
                         List<Bucket> expenseCategories, List<DayPoint> expenseDaily, List<Item> expenseItems,
                         List<DayPoint> workDaily, List<Item> workItems,
                         List<Bucket> habitStats, List<Item> habitItems,
                         List<DayPoint> focusDaily, List<Item> focusItems,
                         List<Item> countdownItems,
                         JsonNode external) {
    }

    // ---------------------------------------------------------------- 周期

    /** 周期的起止（左闭右开）。 */
    private record Span(LocalDate start, LocalDate endExclusive) {
    }

    private Span span(PeriodType type, LocalDate anyDay) {
        if (type == PeriodType.MONTH) {
            YearMonth month = YearMonth.from(anyDay);
            return new Span(month.atDay(1), month.plusMonths(1).atDay(1));
        }
        LocalDate monday = anyDay.with(DayOfWeek.MONDAY);
        return new Span(monday, monday.plusDays(7));
    }

    private Span previous(PeriodType type, Span current) {
        return type == PeriodType.MONTH
                ? span(PeriodType.MONTH, current.start().minusMonths(1))
                : span(PeriodType.WEEK, current.start().minusWeeks(1));
    }

    public Report build(long userId, String periodType, LocalDate anyDay) {
        PeriodType type = PeriodType.parse(periodType);
        Span current = span(type, anyDay);
        Span prev = previous(type, current);

        LocalDate today = LocalDate.now(zone(userId));
        boolean partial = !today.isAfter(current.endExclusive().minusDays(1));
        // 未结束的周期，右边界收到「明天零点」，别把未来算进来
        LocalDate effectiveEnd = partial ? today.plusDays(1) : current.endExclusive();
        if (effectiveEnd.isBefore(current.start())) effectiveEnd = current.start();

        Kpi kpi = kpi(userId, current.start(), effectiveEnd);
        // 对比期一律取完整区间：与「只过了一半的当期」比没有意义
        Kpi previous = kpi(userId, prev.start(), prev.endExclusive());

        List<Item> taskItems = jdbc.query(
                "SELECT t.public_id,t.title,DATE(t.completed_at),l.name FROM task t LEFT JOIN task_list l ON l.id=t.list_id " +
                        "WHERE t.user_id=? AND t.deleted=FALSE AND t.status='COMPLETED' " +
                        "AND t.completed_at>=? AND t.completed_at<? ORDER BY t.completed_at DESC LIMIT " + ITEM_LIMIT,
                (rs, i) -> new Item(rs.getString(2), rs.getDate(3) + " · " + (rs.getString(4) == null ? "收集箱" : rs.getString(4)),
                        null, "/tasks/all?task=" + rs.getString(1)),
                userId, ts(current.start()), ts(effectiveEnd));

        List<Item> expenseItems = new ArrayList<>();
        List<Bucket> expenseCategories = new ArrayList<>();
        List<DayPoint> expenseDaily = new ArrayList<>();
        try {
            expenseItems = jdbc.query(
                    "SELECT x.public_id,COALESCE(c.name,'未分类'),x.occurred_on,x.amount,x.note " +
                            "FROM ledger_transaction x LEFT JOIN ledger_category c ON c.id=x.category_id " +
                            "WHERE x.user_id=? AND x.kind='EXPENSE' AND x.occurred_on>=? AND x.occurred_on<? " +
                            "ORDER BY x.amount DESC LIMIT " + ITEM_LIMIT,
                    (rs, i) -> new Item(rs.getString(2), "¥" + money(rs.getBigDecimal(4)) + " · " + rs.getDate(3),
                            blank(rs.getString(5)), "/ledger/transactions"),
                    userId, date(current.start()), date(effectiveEnd));
            expenseCategories = jdbc.query(
                    "SELECT COALESCE(c.name,'未分类') n, SUM(x.amount) amt, COUNT(*) c " +
                            "FROM ledger_transaction x LEFT JOIN ledger_category c ON c.id=x.category_id " +
                            "WHERE x.user_id=? AND x.kind='EXPENSE' AND x.occurred_on>=? AND x.occurred_on<? " +
                            "GROUP BY n ORDER BY amt DESC LIMIT 8",
                    (rs, i) -> new Bucket(rs.getString(1), decimal(rs.getBigDecimal(2)), rs.getInt(3)),
                    userId, date(current.start()), date(effectiveEnd));
            expenseDaily = jdbc.query(
                    "SELECT occurred_on d, SUM(amount) amt FROM ledger_transaction " +
                            "WHERE user_id=? AND kind='EXPENSE' AND occurred_on>=? AND occurred_on<? " +
                            "GROUP BY occurred_on ORDER BY d",
                    (rs, i) -> new DayPoint(rs.getDate(1).toString(), decimal(rs.getBigDecimal(2))),
                    userId, date(current.start()), date(effectiveEnd));
        } catch (RuntimeException ignored) {
            // 账本表未初始化时该段退化为空
        }

        List<Item> workItems = new ArrayList<>();
        List<DayPoint> workDaily = new ArrayList<>();
        try {
            workItems = jdbc.query(
                    "SELECT date,TIMESTAMPDIFF(MINUTE,start_time,end_time)-COALESCE(lunch_min,0)-COALESCE(rest_min,0) net," +
                            "COALESCE(overtime_min,0) ot,note FROM work_record " +
                            "WHERE user_id=? AND deleted=FALSE AND date>=? AND date<? ORDER BY date DESC LIMIT 31",
                    (rs, i) -> new Item(rs.getDate(1).toString(),
                            hours(Math.max(0, rs.getInt(2))) + "h" + (rs.getInt(3) > 0 ? " · 加班 " + rs.getInt(3) + " 分钟" : ""),
                            blank(rs.getString(4)), "/records"),
                    userId, date(current.start()), date(effectiveEnd));
            workDaily = jdbc.query(
                    "SELECT date d,TIMESTAMPDIFF(MINUTE,start_time,end_time)-COALESCE(lunch_min,0)-COALESCE(rest_min,0) net " +
                            "FROM work_record WHERE user_id=? AND deleted=FALSE AND date>=? AND date<? ORDER BY d",
                    (rs, i) -> new DayPoint(rs.getDate(1).toString(), hours(Math.max(0, rs.getInt(2)))),
                    userId, date(current.start()), date(effectiveEnd));
        } catch (RuntimeException ignored) {
            // 工时表缺失时保持空
        }

        List<Bucket> habitStats = new ArrayList<>();
        List<Item> habitItems = new ArrayList<>();
        try {
            List<Object[]> rows = jdbc.query(
                    "SELECT h.name, c.status, COUNT(*) c FROM habit_checkin c JOIN habit h ON h.id=c.habit_id " +
                            "WHERE c.user_id=? AND c.checkin_date>=? AND c.checkin_date<? GROUP BY h.name, c.status",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)},
                    userId, date(current.start()), date(effectiveEnd));
            Map<String, int[]> byHabit = new LinkedHashMap<>();
            for (Object[] row : rows) {
                String status = (String) row[1];
                int[] slot = byHabit.computeIfAbsent((String) row[0], key -> new int[]{0, 0});
                if ("DONE".equals(status)) slot[0] += (Integer) row[2];
                else if ("MISS".equals(status)) slot[1] += (Integer) row[2];
            }
            for (Map.Entry<String, int[]> entry : byHabit.entrySet()) {
                int done = entry.getValue()[0];
                int miss = entry.getValue()[1];
                habitStats.add(new Bucket(entry.getKey(), done, done + miss));
                habitItems.add(new Item(entry.getKey(), done + " / " + (done + miss),
                        miss > 0 ? "缺卡 " + miss + " 次" : "全勤", "/habits"));
            }
        } catch (RuntimeException ignored) {
            // 习惯表缺失时保持空
        }

        String label = type == PeriodType.MONTH
                ? YearMonth.from(anyDay).toString()
                : current.start() + " ~ " + current.endExclusive().minusDays(1);

        return new Report(type.name(), current.start().toString(),
                current.endExclusive().minusDays(1).toString(), partial, label,
                kpi, previous,
                taskLists(userId, current.start(), effectiveEnd),
                taskDaily(userId, current.start(), effectiveEnd),
                taskItems, expenseCategories, expenseDaily, expenseItems,
                workDaily, workItems, habitStats, habitItems,
                focusDaily(userId, current.start(), effectiveEnd),
                focusItems(userId, current.start(), effectiveEnd),
                countdownItems(userId, current.start(), current.endExclusive()),
                external(userId, type, current.start()));
    }

    // ---------------------------------------------------------------- 分段查询

    private Kpi kpi(long userId, LocalDate start, LocalDate end) {
        Timestamp from = ts(start);
        Timestamp to = ts(end);
        Map<String, Object> taskCounts = jdbc.queryForMap(
                "SELECT COALESCE(SUM(status='COMPLETED' AND completed_at>=? AND completed_at<?),0) completed," +
                        "COALESCE(SUM(created_at>=? AND created_at<?),0) created," +
                        "COALESCE(SUM(status='OPEN'),0) open_cnt," +
                        "COALESCE(SUM(status='OPEN' AND due_at IS NOT NULL AND due_at<?),0) overdue " +
                        "FROM task WHERE user_id=? AND deleted=FALSE",
                from, to, from, to, to, userId);

        double expense = 0;
        double income = 0;
        int expenseCount = 0;
        try {
            List<Object[]> sums = jdbc.query(
                    "SELECT kind, COALESCE(SUM(amount),0) amt, COUNT(*) c FROM ledger_transaction " +
                            "WHERE user_id=? AND occurred_on>=? AND occurred_on<? AND kind IN ('EXPENSE','INCOME') GROUP BY kind",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getBigDecimal(2), rs.getInt(3)},
                    userId, date(start), date(end));
            for (Object[] row : sums) {
                if ("EXPENSE".equals(row[0])) {
                    expense += decimal(row[1]);
                    expenseCount = (Integer) row[2];
                } else {
                    income += decimal(row[1]);
                }
            }
        } catch (RuntimeException ignored) {
        }

        double netMinutes = 0;
        int workDays = 0;
        int overtime = 0;
        try {
            List<Object[]> records = jdbc.query(
                    "SELECT TIMESTAMPDIFF(MINUTE,start_time,end_time)-COALESCE(lunch_min,0)-COALESCE(rest_min,0) net," +
                            "COALESCE(overtime_min,0) ot FROM work_record " +
                            "WHERE user_id=? AND deleted=FALSE AND date>=? AND date<?",
                    (rs, i) -> new Object[]{rs.getInt(1), rs.getInt(2)},
                    userId, date(start), date(end));
            for (Object[] row : records) {
                netMinutes += Math.max(0, (Integer) row[0]);
                overtime += (Integer) row[1];
                workDays += 1;
            }
        } catch (RuntimeException ignored) {
        }

        int habitDone = 0;
        int habitMiss = 0;
        try {
            List<Object[]> rows = jdbc.query(
                    "SELECT status, COUNT(*) c FROM habit_checkin WHERE user_id=? AND checkin_date>=? AND checkin_date<? " +
                            "GROUP BY status",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)},
                    userId, date(start), date(end));
            for (Object[] row : rows) {
                if ("DONE".equals(row[0])) habitDone += (Integer) row[1];
                else if ("MISS".equals(row[0])) habitMiss += (Integer) row[1];
            }
        } catch (RuntimeException ignored) {
        }

        int focusSessions = 0;
        double focusMinutes = 0;
        try {
            Map<String, Object> focus = jdbc.queryForMap(
                    "SELECT COUNT(*) c, COALESCE(SUM(actual_minutes),0) m FROM focus_session " +
                            "WHERE user_id=? AND started_at>=? AND started_at<? AND actual_minutes>0",
                    userId, ts(start), ts(end));
            focusSessions = number(focus.get("c"));
            focusMinutes = decimal(focus.get("m"));
        } catch (RuntimeException ignored) {
            // 专注表缺失时保持 0
        }

        int habitTotal = habitDone + habitMiss;
        return new Kpi(number(taskCounts.get("completed")), number(taskCounts.get("created")),
                number(taskCounts.get("overdue")), number(taskCounts.get("open_cnt")),
                round(expense), round(income), expenseCount,
                round(netMinutes / 60.0), workDays, overtime,
                habitDone, habitMiss, habitTotal == 0 ? 0 : round(habitDone * 100.0 / habitTotal),
                focusSessions, round(focusMinutes / 60.0));
    }

    private List<DayPoint> focusDaily(long userId, LocalDate start, LocalDate end) {
        try {
            return jdbc.query(
                    "SELECT DATE(started_at) d, SUM(actual_minutes) m FROM focus_session " +
                            "WHERE user_id=? AND started_at>=? AND started_at<? AND actual_minutes>0 " +
                            "GROUP BY DATE(started_at) ORDER BY d",
                    (rs, i) -> new DayPoint(rs.getDate(1).toString(), rs.getInt(2)),
                    userId, ts(start), ts(end));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 专注按「任务/习惯」聚合，而不是逐次流水：一天里同一个任务专注三四次很常见，
     * 逐条列出来只会把列表刷满、看不出时间真正花在哪。
     */
    private List<Item> focusItems(long userId, LocalDate start, LocalDate end) {
        try {
            return jdbc.query(
                    "SELECT COALESCE(t.title, h.name, '未关联任务') title, SUM(f.actual_minutes) mins, " +
                            "COUNT(*) cnt, MAX(DATE(f.started_at)) last_day " +
                            "FROM focus_session f LEFT JOIN task t ON t.id=f.task_id LEFT JOIN habit h ON h.id=f.habit_id " +
                            "WHERE f.user_id=? AND f.started_at>=? AND f.started_at<? AND f.actual_minutes>0 " +
                            "GROUP BY COALESCE(t.title, h.name, '未关联任务') ORDER BY mins DESC LIMIT " + ITEM_LIMIT,
                    (rs, i) -> new Item(rs.getString(1),
                            focusDuration(rs.getInt(2)) + " · " + rs.getInt(3) + " 次",
                            rs.getDate(4).toString(), "/tasks/focus"),
                    userId, ts(start), ts(end));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static String focusDuration(int minutes) {
        if (minutes < 60) return minutes + " 分钟";
        double hours = Math.round(minutes / 6.0) / 10.0;
        return hours + " 小时";
    }

    /** 倒数日不是「统计量」，而是清单：本期内到期的排在前面，再补未来 30 天的。 */
    private List<Item> countdownItems(long userId, LocalDate start, LocalDate endExclusive) {
        try {
            LocalDate today = LocalDate.now();
            return jdbc.query(
                    "SELECT c.title, c.target_date, COALESCE(g.name,'') grp FROM countdown c " +
                            "LEFT JOIN countdown_group g ON g.public_id=c.group_id AND g.deleted_at IS NULL " +
                            "WHERE c.user_id=? AND c.deleted_at IS NULL AND c.archived=0 " +
                            "AND c.target_date>=? AND c.target_date<? ORDER BY c.target_date LIMIT 10",
                    (rs, i) -> {
                        LocalDate target = rs.getDate(2).toLocalDate();
                        long days = java.time.temporal.ChronoUnit.DAYS.between(today, target);
                        String when = days == 0 ? "就是今天" : (days > 0 ? "还有 " + days + " 天" : "已过 " + (-days) + " 天");
                        String grp = rs.getString(3);
                        return new Item(rs.getString(1), target + " · " + when,
                                grp == null || grp.isBlank() ? null : grp, "/tasks/countdowns");
                    },
                    userId, date(start), date(endExclusive.plusDays(30)));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private List<Bucket> taskLists(long userId, LocalDate start, LocalDate end) {
        return jdbc.query(
                "SELECT l.name, COUNT(*) c FROM task t JOIN task_list l ON l.id=t.list_id " +
                        "WHERE t.user_id=? AND t.deleted=FALSE AND t.status='COMPLETED' " +
                        "AND t.completed_at>=? AND t.completed_at<? GROUP BY l.name ORDER BY c DESC LIMIT 8",
                (rs, i) -> new Bucket(rs.getString(1), rs.getInt(2), rs.getInt(2)),
                userId, ts(start), ts(end));
    }

    private List<DayPoint> taskDaily(long userId, LocalDate start, LocalDate end) {
        return jdbc.query(
                "SELECT DATE(completed_at) d, COUNT(*) c FROM task " +
                        "WHERE user_id=? AND deleted=FALSE AND status='COMPLETED' AND completed_at>=? AND completed_at<? " +
                        "GROUP BY DATE(completed_at) ORDER BY d",
                (rs, i) -> new DayPoint(rs.getDate(1).toString(), rs.getInt(2)),
                userId, ts(start), ts(end));
    }

    private JsonNode external(long userId, PeriodType type, LocalDate start) {
        try {
            List<String> rows = jdbc.query(
                    "SELECT payload FROM report_external WHERE user_id=? AND period_type=? AND period_start=?",
                    (rs, i) -> rs.getString(1), userId, type.name(), date(start));
            if (rows.isEmpty()) return null;
            return mapper.readTree(rows.get(0));
        } catch (Exception exception) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 工具

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

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    private static Date date(LocalDate date) {
        return Date.valueOf(date);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static double hours(int minutes) {
        return Math.round(minutes / 60.0 * 10) / 10.0;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static double decimal(Object value) {
        if (value instanceof BigDecimal amount) return amount.doubleValue();
        if (value instanceof Number n) return n.doubleValue();
        return 0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
