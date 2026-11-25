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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/feishu", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Feishu")
@PreAuthorize("isAuthenticated()")
public class FeishuBindingController {
    private final FeishuConfig config;
    private final FeishuBotService bot;
    private final CurrentUserResolver currentUser;

    public FeishuBindingController(FeishuConfig config, FeishuBotService bot, CurrentUserResolver currentUser) {
        this.config = config;
        this.bot = bot;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    @Operation(summary = "飞书连接状态")
    public ApiResponse<Status> status() {
        FeishuBotService.BindingView binding = bot.bindingOf(currentUser.id());
        return ApiResponse.ok(new Status(config.ready(), config.encrypted(), binding));
    }

    @PostMapping("/bind-code")
    @Operation(summary = "生成 6 位绑定码（10 分钟有效）")
    public ApiResponse<BindCode> bindCode() {
        String code = bot.createBindCode(currentUser.id());
        return ApiResponse.ok(new BindCode(code, 600, "在飞书里给机器人发送：绑定 " + code));
    }

    @DeleteMapping("/binding")
    @Operation(summary = "解除飞书绑定")
    public ApiResponse<Boolean> unbind() {
        bot.unbind(currentUser.id());
        return ApiResponse.ok(true);
    }

    public record Status(boolean configured, boolean encrypted, FeishuBotService.BindingView binding) {
    }

    public record BindCode(String code, int expiresInSeconds, String hint) {
    }
}
