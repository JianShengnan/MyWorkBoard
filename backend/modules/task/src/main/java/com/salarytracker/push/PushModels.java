package com.salarytracker.push;

import java.util.Map;

/** 站外推送的数据形状：通道配置、偏好、测试结果、投递记录。 */
public final class PushModels {
    private PushModels() {
    }

    /** ready=false 表示必填项缺失（或服务端 SMTP 未配置），前端据此提示「还没配好」 */
    public record Channel(long id, String kind, boolean enabled, boolean ready, Map<String, String> config,
                          String lastTestAt, Boolean lastTestOk, String lastError, String lastSentAt, long revision) {
    }

    public record ChannelCommand(Boolean enabled, Map<String, String> config, Long revision) {
    }

    public record Preference(boolean taskReminder, boolean checklistReminder, boolean habitDigest,
                             int habitDigestHour, String quietFrom, String quietTo, long revision) {
    }

    public record PreferenceCommand(Boolean taskReminder, Boolean checklistReminder, Boolean habitDigest,
                                    Integer habitDigestHour, String quietFrom, String quietTo, Long revision) {
    }

    public record TestResult(boolean ok, String detail) {
    }

    public record Deleted(boolean deleted) {
    }

    public record Delivery(String kind, String event, String status, String title, String detail, String createdAt) {
    }

    /** link 传站内相对路径，分发时统一拼成绝对地址 */
    public record Message(String title, String body, String link) {
    }
}
