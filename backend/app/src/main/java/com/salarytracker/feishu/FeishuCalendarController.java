package com.salarytracker.feishu;

import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.platform.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 飞书日历同步：开关、范围、立即同步。 */
@RestController
@RequestMapping(value = "/api/v1/feishu/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Feishu")
@PreAuthorize("isAuthenticated()")
public class FeishuCalendarController {
    private final FeishuCalendarService service;
    private final CurrentUserResolver currentUser;

    public FeishuCalendarController(FeishuCalendarService service, CurrentUserResolver currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    @Operation(summary = "飞书日历同步状态")
    public ApiResponse<FeishuCalendarService.SyncStatus> status() {
        return ApiResponse.ok(service.status(currentUser.id()));
    }

    @PostMapping("/enable")
    @Operation(summary = "开启同步（首次会创建并共享「WorkBoard」日历）")
    public ApiResponse<FeishuCalendarService.SyncResult> enable() {
        return ApiResponse.ok(service.enable(currentUser.id()));
    }

    @PostMapping("/sync")
    @Operation(summary = "立即同步一次")
    public ApiResponse<FeishuCalendarService.SyncResult> sync() {
        return ApiResponse.ok(service.sync(currentUser.id()));
    }

    @PutMapping("/scope")
    @Operation(summary = "调整同步内容（任务 / 倒数日）")
    public ApiResponse<FeishuCalendarService.SyncStatus> scope(@RequestBody ScopeCommand body) {
        service.updateScope(currentUser.id(), body.includeTasks(), body.includeCountdowns());
        return ApiResponse.ok(service.status(currentUser.id()));
    }

    @DeleteMapping
    @Operation(summary = "关闭同步（飞书里的日历与已有日程保留）")
    public ApiResponse<Boolean> disable() {
        service.disable(currentUser.id());
        return ApiResponse.ok(true);
    }

    public record ScopeCommand(Boolean includeTasks, Boolean includeCountdowns) {
    }
}
