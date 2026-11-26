package com.salarytracker.report;

import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.platform.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 周报 / 月报。
 *
 * <p>数据与总结分两个端点：页面先拿数据立即首屏，再单独取总结（可能要调模型几秒），
 * 避免打开页面时要干等模型。</p>
 */
@RestController
@RequestMapping(value = "/api/v1/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "报表")
@PreAuthorize("isAuthenticated()")
public class ReportController {
    public record ReportPayload(ReportService.Report report, ReportSummaryService.Summary summary) {
    }

    private final ReportService reports;
    private final ReportSummaryService summaries;
    private final CurrentUserResolver currentUser;

    public ReportController(ReportService reports, ReportSummaryService summaries, CurrentUserResolver currentUser) {
        this.reports = reports;
        this.summaries = summaries;
        this.currentUser = currentUser;
    }

    @GetMapping("/weekly")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "周报数据，date 落在哪一周就返回哪一周")
    public ApiResponse<ReportPayload> weekly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean withSummary) {
        return ApiResponse.ok(build("WEEK", date, withSummary));
    }

    @GetMapping("/monthly")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "月报数据，自然月口径")
    public ApiResponse<ReportPayload> monthly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean withSummary) {
        return ApiResponse.ok(build("MONTH", date, withSummary));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "周期总结（无缓存时调用模型生成，refresh=true 强制重写）")
    public ApiResponse<ReportSummaryService.Summary> summary(
            @RequestParam(defaultValue = "WEEK") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean refresh) {
        LocalDate day = date == null ? LocalDate.now() : date;
        String normalized = type == null ? "WEEK" : type.trim().toUpperCase();
        LocalDate start = start(normalized, day);
        return ApiResponse.ok(summaries.get(currentUser.id(), normalized, start, refresh));
    }

    private ReportPayload build(String type, LocalDate date, boolean withSummary) {
        long userId = currentUser.id();
        LocalDate day = date == null ? LocalDate.now() : date;
        ReportService.Report report = reports.build(userId, type, day);
        ReportSummaryService.Summary summary = withSummary
                ? summaries.get(userId, type, LocalDate.parse(report.start()), false)
                : summaries.cached(userId, type, LocalDate.parse(report.start()));
        return new ReportPayload(report, summary);
    }

    private LocalDate start(String type, LocalDate day) {
        return LocalDate.parse(reports.build(currentUser.id(),
                "MONTH".equals(type) ? "MONTH" : "WEEK", day).start());
    }
}
