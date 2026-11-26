package com.salarytracker.ai.tool.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salarytracker.ai.tool.DomainTool;
import com.salarytracker.ai.tool.ToolDefinition;
import com.salarytracker.ai.tool.ToolResult;
import com.salarytracker.ai.tool.ToolRisk;
import com.salarytracker.ai.tool.ToolSchemas;
import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.report.ReportService;
import com.salarytracker.report.ReportSummaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 报表类 MCP 工具：让 WorkBuddy 这类外部客户端取到聚合数据，再用自己的连接器补充外部信息。
 *
 * <p>只读（R1），不涉及审批流。总结默认读缓存，传 {@code withSummary=true} 才会触发模型生成，
 * 避免外部客户端一次调用把模型调用也捎上、白等几秒。</p>
 */
@Configuration
public class ReportToolsConfiguration {
    @Bean
    public DomainTool reportWeeklyTool(ReportService reports, ReportSummaryService summaries,
                                       CurrentUserResolver currentUser, ObjectMapper mapper) {
        return tool("report.weekly",
                "取当前用户某一周的汇总：任务、账目、工时、习惯的关键指标与明细列表，"
                        + "附上一周期对比。date 落在哪一周就返回哪一周（周一至周日）。",
                schema(mapper, "日期 yyyy-MM-dd，落在目标周内即可；缺省为本日"), mapper,
                input -> build(reports, summaries, currentUser, mapper, "WEEK", input));
    }

    @Bean
    public DomainTool reportMonthlyTool(ReportService reports, ReportSummaryService summaries,
                                        CurrentUserResolver currentUser, ObjectMapper mapper) {
        return tool("report.monthly",
                "取当前用户某一个自然月的汇总，口径与 report.weekly 一致，只是周期换成一个整月。",
                schema(mapper, "日期 yyyy-MM-dd，落在目标月内即可；缺省为本日"), mapper,
                input -> build(reports, summaries, currentUser, mapper, "MONTH", input));
    }

    private ObjectNode schema(ObjectMapper mapper, String dateHint) {
        ObjectNode schema = ToolSchemas.object(mapper);
        ToolSchemas.stringProperty(schema, "date", dateHint, "date");
        ToolSchemas.booleanProperty(schema, "withSummary", "是否触发模型生成周期总结（默认只读缓存）");
        return schema;
    }

    private Map<String, Object> build(ReportService reports, ReportSummaryService summaries,
                                      CurrentUserResolver currentUser, ObjectMapper mapper,
                                      String type, JsonNode input) {
        long userId = currentUser.id();
        String raw = input == null ? null : input.path("date").asText(null);
        LocalDate day = raw == null || raw.isBlank() ? LocalDate.now() : LocalDate.parse(raw);
        boolean withSummary = input != null && input.path("withSummary").asBoolean(false);

        ReportService.Report report = reports.build(userId, type, day);
        LocalDate start = LocalDate.parse(report.start());
        ReportSummaryService.Summary summary = withSummary
                ? summaries.get(userId, type, start, false)
                : summaries.cached(userId, type, start);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report", report);
        payload.put("summary", summary);
        payload.put("hint", "外部数据（邮件、音乐、GitHub 等）不在本站，需要由调用方补充后再组装最终报告");
        return payload;
    }

    private DomainTool tool(String name, String description, ObjectNode schema, ObjectMapper mapper,
                            Reader reader) {
        ToolDefinition definition = new ToolDefinition(name, 1, description, ToolRisk.R1,
                Set.of("task:read"), schema);
        return new DomainTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(JsonNode input) {
                Object value = reader.read(input == null ? mapper.createObjectNode() : input);
                JsonNode content = mapper.valueToTree(value);
                return ToolResult.completed(summarize(content), content);
            }
        };
    }

    private String summarize(JsonNode content) {
        JsonNode report = content == null ? null : content.get("report");
        if (report == null) return "已返回报表";
        String start = report.path("start").asText("");
        String end = report.path("end").asText("");
        int completed = report.path("kpi").path("completed").asInt(0);
        double netHours = report.path("kpi").path("netHours").asDouble(0);
        return "%s ~ %s：完成 %d 项，净工时 %.1f 小时".formatted(start, end, completed, netHours);
    }

    private interface Reader {
        Object read(JsonNode input);
    }
}
