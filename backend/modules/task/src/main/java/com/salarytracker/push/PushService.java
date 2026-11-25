package com.salarytracker.push;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.platform.ConflictException;
import com.salarytracker.platform.NotFoundException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 站外推送的配置与分发。
 *
 * <p>分发不阻塞调用方：任务提醒的扫描在事务里，外部 HTTP 慢不能拖住它，因此发送走单独的
 * 两条守护线程；失败只写 push_delivery 与通道的 last_error，不影响站内收件箱。</p>
 */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final Set<String> KINDS = Set.of("FEISHU", "BARK", "EMAIL");

    private final JdbcTemplate jdbc;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper mapper;
    private final Map<String, PushSender> senders;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "push-sender");
        thread.setDaemon(true);
        return thread;
    });

    public PushService(JdbcTemplate jdbc, CurrentUserResolver currentUser, ObjectMapper mapper, List<PushSender> senders) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.senders = senders.stream().collect(Collectors.toMap(PushSender::kind, Function.identity()));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public List<PushModels.Channel> channels() {
        return queryChannels(currentUser.id(), null);
    }

    public List<PushModels.Channel> channels(long userId) {
        return queryChannels(userId, null);
    }

    @Transactional
    public PushModels.Channel save(String kind, PushModels.ChannelCommand command) {
        long userId = currentUser.id();
        String normalized = normalizeKind(kind);
        if (command == null) throw new IllegalArgumentException("推送配置不能为空");
        Map<String, String> config = sanitize(normalized, command.config());
        Long currentRevision = jdbc.query("SELECT revision FROM push_channel WHERE user_id=? AND kind=?",
                result -> result.next() ? result.getLong(1) : null, userId, normalized);
        if (currentRevision != null && command.revision() != null && command.revision() != currentRevision) {
            throw new ConflictException("推送通道已被修改，请刷新后重试", currentRevision);
        }
        boolean enabled = command.enabled() == null || command.enabled();
        try {
            jdbc.update("INSERT INTO push_channel(public_id,user_id,kind,enabled,config) VALUES(?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),config=VALUES(config),revision=revision+1",
                    UUID.randomUUID().toString(), userId, normalized, enabled, mapper.writeValueAsString(config));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("推送配置无法保存");
        }
        List<PushModels.Channel> rows = queryChannels(userId, normalized);
        if (rows.isEmpty()) throw new IllegalStateException("推送通道保存失败");
        return rows.get(0);
    }

    @Transactional
    public void delete(String kind) {
        jdbc.update("DELETE FROM push_channel WHERE user_id=? AND kind=?", currentUser.id(), normalizeKind(kind));
    }

    /** 主动测试：不要求通道已启用，但要求配置齐全 */
    public PushModels.TestResult test(String kind) {
        long userId = currentUser.id();
        String normalized = normalizeKind(kind);
        List<PushModels.Channel> rows = queryChannels(userId, normalized);
        if (rows.isEmpty()) throw new NotFoundException("尚未配置该推送通道");
        PushModels.Channel channel = rows.get(0);
        PushSender sender = senders.get(normalized);
        if (sender == null) throw new IllegalArgumentException("不支持的推送通道");
        try {
            sender.send(channel.config(), new PushModels.Message("WorkBoard 测试推送",
                    "收到这条说明「" + label(normalized) + "」通道已经打通，任务提醒与习惯汇总会推到这里。", absolute("/settings")));
            jdbc.update("UPDATE push_channel SET last_test_at=CURRENT_TIMESTAMP(3),last_test_ok=TRUE,last_error=NULL WHERE id=?",
                    channel.id());
            logDelivery(userId, normalized, "TEST", "SENT", "通道测试", null);
            return new PushModels.TestResult(true, "发送成功");
        } catch (Exception exception) {
            String detail = PushHttp.truncate(exception.getMessage(), 300);
            jdbc.update("UPDATE push_channel SET last_test_at=CURRENT_TIMESTAMP(3),last_test_ok=FALSE,last_error=? WHERE id=?",
                    detail, channel.id());
            logDelivery(userId, normalized, "TEST", "FAILED", "通道测试", detail);
            return new PushModels.TestResult(false, detail);
        }
    }

    public PushModels.Preference preference() {
        return preference(currentUser.id());
    }

    public PushModels.Preference preference(long userId) {
        List<PushModels.Preference> rows = jdbc.query("SELECT task_reminder,checklist_reminder,habit_digest,habit_digest_hour," +
                        "quiet_from,quiet_to,revision FROM push_preference WHERE user_id=?",
                (row, number) -> new PushModels.Preference(row.getBoolean(1), row.getBoolean(2), row.getBoolean(3),
                        row.getInt(4), row.getString(5), row.getString(6), row.getLong(7)), userId);
        return rows.isEmpty() ? new PushModels.Preference(true, true, true, 21, null, null, 0) : rows.get(0);
    }

    @Transactional
    public PushModels.Preference savePreference(PushModels.PreferenceCommand command) {
        long userId = currentUser.id();
        if (command == null) throw new IllegalArgumentException("提醒偏好不能为空");
        PushModels.Preference before = preference(userId);
        if (before.revision() > 0 && command.revision() != null && command.revision() != before.revision()) {
            throw new ConflictException("提醒偏好已被修改，请刷新后重试", before.revision());
        }
        boolean task = command.taskReminder() == null ? before.taskReminder() : command.taskReminder();
        boolean checklist = command.checklistReminder() == null ? before.checklistReminder() : command.checklistReminder();
        boolean habit = command.habitDigest() == null ? before.habitDigest() : command.habitDigest();
        int hour = command.habitDigestHour() == null ? before.habitDigestHour() : command.habitDigestHour();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("习惯汇总时间需要是 0–23 点");
        String quietFrom = normalizeClock(command.quietFrom() == null ? before.quietFrom() : command.quietFrom());
        String quietTo = normalizeClock(command.quietTo() == null ? before.quietTo() : command.quietTo());
        if ((quietFrom == null) != (quietTo == null)) throw new IllegalArgumentException("静默时段需要同时填写开始和结束");
        jdbc.update("INSERT INTO push_preference(user_id,task_reminder,checklist_reminder,habit_digest,habit_digest_hour," +
                        "quiet_from,quiet_to) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE task_reminder=VALUES(task_reminder)," +
                        "checklist_reminder=VALUES(checklist_reminder),habit_digest=VALUES(habit_digest)," +
                        "habit_digest_hour=VALUES(habit_digest_hour),quiet_from=VALUES(quiet_from),quiet_to=VALUES(quiet_to)," +
                        "revision=revision+1",
                userId, task, checklist, habit, hour, quietFrom, quietTo);
        return preference(userId);
    }

    public List<PushModels.Delivery> deliveries(int limit) {
        int size = Math.min(50, Math.max(1, limit));
        return jdbc.query("SELECT kind,event,status,title,detail,created_at FROM push_delivery WHERE user_id=? " +
                        "ORDER BY id DESC LIMIT ?",
                (row, number) -> new PushModels.Delivery(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), instant(row.getTimestamp(6))), currentUser.id(), size);
    }

    /**
     * 分发一条外部提醒。event 取 TASK_REMINDER / CHECKLIST_REMINDER / HABIT_DIGEST，link 传站内路径。
     * 调用方不必处理异常：任何失败都只落到投递日志。
     */
    public void dispatch(long userId, String event, String title, String body, String link) {
        try {
            PushModels.Preference preference = preference(userId);
            if (!allowed(preference, event)) {
                logDelivery(userId, "-", event, "SKIPPED", title, "该类提醒已在设置里关闭");
                return;
            }
            if (quiet(preference, timezone(userId))) {
                logDelivery(userId, "-", event, "SKIPPED", title, "静默时段");
                return;
            }
            List<PushModels.Channel> targets = queryChannels(userId, null).stream()
                    .filter(channel -> channel.enabled() && channel.ready()).toList();
            if (targets.isEmpty()) return;
            PushModels.Message message = new PushModels.Message(title, body, absolute(link));
            for (PushModels.Channel channel : targets) {
                PushSender sender = senders.get(channel.kind());
                if (sender == null) continue;
                executor.execute(() -> deliver(userId, sender, channel, event, message));
            }
            jdbc.update("DELETE FROM push_delivery WHERE user_id=? AND created_at<TIMESTAMPADD(DAY,-30,CURRENT_TIMESTAMP(3))", userId);
        } catch (Exception exception) {
            log.warn("推送分发失败 userId={} event={}: {}", userId, event, exception.getMessage());
        }
    }

    private void deliver(long userId, PushSender sender, PushModels.Channel channel, String event, PushModels.Message message) {
        try {
            sender.send(channel.config(), message);
            jdbc.update("UPDATE push_channel SET last_sent_at=CURRENT_TIMESTAMP(3),last_error=NULL WHERE id=?", channel.id());
            logDelivery(userId, channel.kind(), event, "SENT", message.title(), null);
        } catch (Exception exception) {
            String detail = PushHttp.truncate(exception.getMessage(), 300);
            jdbc.update("UPDATE push_channel SET last_error=? WHERE id=?", detail, channel.id());
            logDelivery(userId, channel.kind(), event, "FAILED", message.title(), detail);
            log.info("推送失败 userId={} kind={}: {}", userId, channel.kind(), detail);
        }
    }

    private void logDelivery(long userId, String kind, String event, String status, String title, String detail) {
        jdbc.update("INSERT INTO push_delivery(user_id,kind,event,status,title,detail) VALUES(?,?,?,?,?,?)",
                userId, kind, event, status, PushHttp.truncate(title, 200), PushHttp.truncate(detail, 500));
    }

    private List<PushModels.Channel> queryChannels(long userId, String kind) {
        String sql = "SELECT id,kind,enabled,config,last_test_at,last_test_ok,last_error,last_sent_at,revision " +
                "FROM push_channel WHERE user_id=?" + (kind == null ? "" : " AND kind=?") + " ORDER BY FIELD(kind,'FEISHU','BARK','EMAIL'),id";
        Object[] args = kind == null ? new Object[]{userId} : new Object[]{userId, kind};
        return jdbc.query(sql, (row, number) -> {
            Map<String, String> config = parse(row.getString("config"));
            String rowKind = row.getString("kind");
            PushSender sender = senders.get(rowKind);
            Timestamp testAt = row.getTimestamp("last_test_at");
            Timestamp sentAt = row.getTimestamp("last_sent_at");
            Object testOk = row.getObject("last_test_ok");
            return new PushModels.Channel(row.getLong("id"), rowKind, row.getBoolean("enabled"),
                    sender != null && sender.ready(config), config,
                    testAt == null ? null : testAt.toInstant().toString(),
                    testOk == null ? null : row.getBoolean("last_test_ok"),
                    row.getString("last_error"),
                    sentAt == null ? null : sentAt.toInstant().toString(), row.getLong("revision"));
        }, args);
    }

    private Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, String> sanitize(String kind, Map<String, String> raw) {
        Map<String, String> input = raw == null ? Map.of() : raw;
        Map<String, String> config = new LinkedHashMap<>();
        switch (kind) {
            case "FEISHU" -> {
                String webhook = clean(input.get("webhook"), 500);
                if (webhook.isEmpty()) throw new IllegalArgumentException("飞书 Webhook 不能为空");
                if (!webhook.startsWith("http://") && !webhook.startsWith("https://")) {
                    throw new IllegalArgumentException("飞书 Webhook 需要以 http(s):// 开头");
                }
                config.put("webhook", webhook);
                config.put("secret", clean(input.get("secret"), 200));
            }
            case "BARK" -> {
                String key = clean(input.get("key"), 200);
                if (key.isEmpty()) throw new IllegalArgumentException("Bark Device Key 不能为空");
                config.put("key", key);
                config.put("server", clean(input.get("server"), 300));
            }
            case "EMAIL" -> {
                String to = clean(input.get("to"), 300);
                if (!to.contains("@")) throw new IllegalArgumentException("收件邮箱格式不正确");
                config.put("to", to);
            }
            default -> throw new IllegalArgumentException("不支持的推送通道");
        }
        return config;
    }

    private boolean allowed(PushModels.Preference preference, String event) {
        return switch (event) {
            case "TASK_REMINDER" -> preference.taskReminder();
            case "CHECKLIST_REMINDER" -> preference.checklistReminder();
            case "HABIT_DIGEST" -> preference.habitDigest();
            default -> true;
        };
    }

    private boolean quiet(PushModels.Preference preference, String timezone) {
        String from = normalizeClock(preference.quietFrom());
        String to = normalizeClock(preference.quietTo());
        if (from == null || to == null) return false;
        LocalTime now;
        try {
            now = LocalTime.now(ZoneId.of(timezone));
        } catch (Exception exception) {
            now = LocalTime.now();
        }
        LocalTime start = LocalTime.parse(from);
        LocalTime end = LocalTime.parse(to);
        if (start.equals(end)) return false;
        return start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end);
    }

    private String timezone(long userId) {
        List<String> rows = jdbc.queryForList("SELECT timezone FROM app_user WHERE id=?", String.class, userId);
        return rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank() ? "Asia/Shanghai" : rows.get(0);
    }

    private String absolute(String link) {
        if (link == null || link.isBlank()) return "";
        if (link.startsWith("http://") || link.startsWith("https://")) return link;
        String base = System.getenv("APP_PUBLIC_URL");
        if (base == null || base.isBlank()) base = "http://jsn1024.cn";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + (link.startsWith("/") ? link : "/" + link);
    }

    private String normalizeKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) throw new IllegalArgumentException("不支持的推送通道");
        return normalized;
    }

    private String normalizeClock(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (!trimmed.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) throw new IllegalArgumentException("时间需要是 HH:mm");
        return trimmed;
    }

    private String clean(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private String label(String kind) {
        return switch (kind) {
            case "FEISHU" -> "飞书群机器人";
            case "BARK" -> "Bark";
            case "EMAIL" -> "邮件";
            default -> kind;
        };
    }
}
