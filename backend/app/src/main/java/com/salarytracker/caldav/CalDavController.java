package com.salarytracker.caldav;

import com.salarytracker.platform.ApiResponse;
import com.salarytracker.identity.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CalDAV 日历同步的设置与手动触发。 */
@RestController
@RequestMapping(value = "/api/v1/caldav", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "日历同步")
public class CalDavController {
    public record ToggleCommand(Boolean enabled) {
    }

    private final CalDavService caldav;
    private final CurrentUserResolver currentUser;

    public CalDavController(CalDavService caldav, CurrentUserResolver currentUser) {
        this.caldav = caldav;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "日历同步状态与订阅地址")
    public ApiResponse<CalDavService.SyncStatus> status() {
        return ApiResponse.ok(caldav.status(currentUser.id()));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('task:write')")
    @Operation(summary = "开启或关闭日历同步")
    public ApiResponse<CalDavService.SyncStatus> toggle(@RequestBody ToggleCommand command) {
        long userId = currentUser.id();
        caldav.setEnabled(userId, command == null || command.enabled() == null || command.enabled());
        return ApiResponse.ok(caldav.status(userId));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('task:write')")
    @Operation(summary = "立即同步一次")
    public ApiResponse<CalDavService.SyncResult> sync() {
        return ApiResponse.ok(caldav.sync(currentUser.id()));
    }
}
