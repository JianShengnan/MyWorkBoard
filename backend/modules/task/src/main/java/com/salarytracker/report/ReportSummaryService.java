package com.salarytracker.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salarytracker.platform.ai.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * 周期总结：把聚合数据交给模型写成一段人话，按 (用户, 周期类型, 周期起点) 缓存。
 *
 * <p>缓存是必要的：模型调用要几秒，页面每次打开都等一次体验很差；
 * 而同一周期的数据在一天内几乎不变，重复生成也没有额外价值。</p>
 */
@Service
public class ReportSummaryService {
    private static final Logger log = LoggerFactory.getLogger(ReportSummaryService.class);

    private final JdbcTemplate jdbc;
    private final LlmGateway llm;
    private final ReportService reports;
    private final ObjectMapper mapper;

    public ReportSummaryService(JdbcTemplate jdbc, LlmGateway llm, ReportService reports, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.reports = reports;
        this.mapper = mapper;
    }

    public record Summary(String content, boolean cached) {
    }

    /** 只读缓存，不触发模型调用（页面首屏用它，避免卡在模型上）。 */
    public Summary cached(long userId, String periodType, LocalDate start) {
        List<String> rows = jdbc.query(
                "SELECT content FROM report_summary WHERE user_id=? AND period_type=? AND period_start=?",
                (rs, i) -> rs.getString(1), userId, normalize(periodType), Date.valueOf(start));
        return rows.isEmpty() ? new Summary(null, false) : new Summary(rows.get(0), true);
    }

    /** 取缓存，没有就生成。force=true 时强制重写。 */
    public Summary get(long userId, String periodType, LocalDate start, boolean force) {
        if (!force) {
            Summary hit = cached(userId, periodType, start);
            if (hit.content() != null && !hit.content().isBlank()) return hit;
        }
        return new Summary(generate(userId, periodType, start), false);
    }

    private String generate(long userId, String periodType, LocalDate start) {
        ReportService.Report report = reports.build(userId, periodType, start);
        String prompt = buildPrompt(report);
        String content;
        try {
            LlmGateway.ChatResponse response = llm.chat(prompt);
            content = response == null ? null : response.content();
        } catch (RuntimeException exception) {
            log.warn("周期总结生成失败 user={} type={}：{}", userId, periodType, exception.getMessage());
            content = fallback(report);
        }
        if (content == null || content.isBlank()) content = fallback(report);
        content = content.trim();
        jdbc.update("INSERT INTO report_summary(user_id,period_type,period_start,content,model) VALUES(?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE content=VALUES(content),model=VALUES(model)," +
                        "generated_at=CURRENT_TIMESTAMP(3)",
                userId, normalize(periodType), Date.valueOf(start), content, "deepseek");
        return content;
    }

    private String buildPrompt(ReportService.Report report) {
        StringBuilder data = new StringBuilder();
        ReportService.Kpi kpi = report.kpi();
        data.append("周期：").append(report.start()).append(" ~ ").append(report.end());
        if (report.partial()) data.append("（进行中，统计截至今天）");
        data.append("\n完成任务：").append(kpi.completed()).append("，新建 ").append(kpi.created())
                .append("，逾期未完成 ").append(kpi.overdue()).append("，在办 ").append(kpi.open());
        data.append("\n账目：支出 ").append(kpi.expense()).append(" 元（").append(kpi.expenseCount())
                .append(" 笔），收入 ").append(kpi.income()).append(" 元");
        if (!report.expenseCategories().isEmpty()) {
            data.append("；支出前几项：");
            report.expenseCategories().stream().limit(4)
                    .forEach(row -> data.append(row.label()).append(" ").append((int) row.value()).append(" 元，"));
        }
        data.append("\n工时：出勤 ").append(kpi.workDays()).append(" 天，净工时 ").append(kpi.netHours())
                .append(" 小时，加班 ").append(kpi.overtimeMinutes()).append(" 分钟");
        data.append("\n习惯：完成 ").append(kpi.habitDone()).append(" 次，缺卡 ").append(kpi.habitMiss())
                .append(" 次，完成率 ").append(kpi.habitRate()).append("%");
        data.append("\n专注：").append(kpi.focusSessions()).append(" 次，共 ")
                .append(kpi.focusHours()).append(" 小时");

        ReportService.Kpi prev = report.previous();
        data.append("\n上一周期对比：完成任务 ").append(prev.completed()).append("，支出 ")
                .append(prev.expense()).append(" 元，净工时 ").append(prev.netHours()).append(" 小时");

        return """
                你是个人数据助理，正在为一位开发者写周期回顾。下面是他在%s的全部采集数据。
                请写一段 180-260 字的中文总结，要求：
                1. 第一句给结论：这个周期的整体状态（不要用「总的来说」这种空话开头）；
                2. 指出一个最值得注意的数字或异常，并解释它意味着什么（和上一周期比）；
                3. 结尾给两条具体、可执行的建议，不要泛泛而谈。
                不要使用 markdown 标题或列表符号，用自然段落；不要复述所有数字，只挑关键的说。

                数据：
                %s
                """.formatted("月" .equals(report.periodType()) ? "月" : "周", data);
    }

    /** 模型不可用时的兜底：用模板把关键数字讲清楚，至少不会是一片空白。 */
    private String fallback(ReportService.Report report) {
        ReportService.Kpi kpi = report.kpi();
        StringBuilder text = new StringBuilder();
        text.append("本周期完成 ").append(kpi.completed()).append(" 项任务，净工时 ")
                .append(kpi.netHours()).append(" 小时");
        if (kpi.expense() > 0) {
            text.append("，支出 ").append((int) kpi.expense()).append(" 元");
        }
        text.append("。");
        if (kpi.overdue() > 0) {
            text.append("目前仍有 ").append(kpi.overdue()).append(" 项已逾期未完成，建议先清掉这些再开新任务。");
        }
        if (kpi.habitMiss() > 0) {
            text.append("习惯有 ").append(kpi.habitMiss()).append(" 次缺卡，可以看看是不是集中在某几天。");
        }
        text.append("（模型暂不可用，以上为根据数据自动生成的摘要。）");
        return text.toString();
    }

    private static String normalize(String periodType) {
        return periodType == null || periodType.isBlank() ? "WEEK" : periodType.trim().toUpperCase();
    }
}
