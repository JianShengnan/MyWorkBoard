package com.salarytracker.push;

import com.salarytracker.platform.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/push", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Push")
public class PushController {
    private final PushService push;

    public PushController(PushService push) {
        this.push = push;
    }

    @GetMapping("/channels")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "推送通道列表")
    public ApiResponse<List<PushModels.Channel>> channels() {
        return ApiResponse.ok(push.channels());
    }

    @PutMapping("/channels/{kind}")
    @PreAuthorize("hasAuthority('task:write')")
    @Operation(summary = "保存推送通道（不存在则新建）")
    public ApiResponse<PushModels.Channel> save(@PathVariable String kind, @RequestBody PushModels.ChannelCommand command) {
        return ApiResponse.ok(push.save(kind, command));
    }

    @DeleteMapping("/channels/{kind}")
    @PreAuthorize("hasAuthority('task:write')")
    @Operation(summary = "删除推送通道")
    public ApiResponse<PushModels.Deleted> delete(@PathVariable String kind) {
        push.delete(kind);
        return ApiResponse.ok(new PushModels.Deleted(true));
    }

    @PostMapping("/channels/{kind}/test")
    @PreAuthorize("hasAuthority('task:write')")
    @Operation(summary = "发送一条测试消息")
    public ApiResponse<PushModels.TestResult> test(@PathVariable String kind) {
        return ApiResponse.ok(push.test(kind));
    }

    @GetMapping("/preference")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "提醒范围与静默时段")
    public ApiResponse<PushModels.Preference> preference() {
        return ApiResponse.ok(push.preference());
    }

    @PutMapping("/preference")
    @PreAuthorize("hasAuthority('task:write')")
    @Operation(summary = "保存提醒偏好")
    public ApiResponse<PushModels.Preference> savePreference(@RequestBody PushModels.PreferenceCommand command) {
        return ApiResponse.ok(push.savePreference(command));
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('task:read')")
    @Operation(summary = "最近推送记录")
    public ApiResponse<List<PushModels.Delivery>> deliveries(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(push.deliveries(limit));
    }
}
