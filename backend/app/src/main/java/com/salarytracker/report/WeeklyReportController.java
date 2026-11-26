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

/** 周报数据：供前端图表页面使用。 */
@RestController
@RequestMapping(value = "/api/v1/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "报表")
@PreAuthorize("isAuthenticated()")
public class WeeklyReportController {
    private final WeeklyReportService service;
    private final CurrentUserResolver currentUser;

    public WeeklyReportController(WeeklyReportService service, CurrentUserResolver currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/weekly")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "周报聚合数据，date 落在哪一周就返回哪一周")
    public ApiResponse<WeeklyReportService.WeeklyReport> weekly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        return ApiResponse.ok(service.weekly(currentUser.id(), day));
    }
}
