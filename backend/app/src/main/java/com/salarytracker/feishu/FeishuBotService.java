package com.salarytracker.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salarytracker.ai.AgentOrchestrator;
import com.salarytracker.ai.session.AgentSessionRepository;
import com.salarytracker.identity.AuthService;
import com.salarytracker.identity.CurrentUser;
import com.salarytracker.platform.ai.LlmGateway;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 飞书私聊的消息处理：绑定、帮助、以及把普通文本交给站内的 AI 助手。
 *
 * <p>关键约束：AI 编排器通过 {@code CurrentUserResolver} 读 SecurityContext 来决定能用哪些工具，
 * 而飞书回调线程没有登录态。所以这里在后台线程里按绑定关系把该用户「临时登录」进来，
 * 用完清掉，既不污染其他线程，也不用给 AI 模块开一条旁路。</p>
 */
@Service
public class FeishuBotService {
    private static final Logger log = LoggerFactory.getLogger(FeishuBotService.class);
    private static final String SESSION_TITLE = "飞书对话";
    private static final int MAX_TEXT = 4_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentOrchestrator orchestrator;
    private final AgentSessionRepository sessions;
    private final AuthService auth;
    private final FeishuClient client;
    private final FeishuCards cards;

    private final ExecutorService worker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "feishu-bot");
        thread.setDaemon(true);
        return thread;
    });

    public FeishuBotService(JdbcTemplate jdbc, ObjectMapper mapper, AgentOrchestrator orchestrator,
                            AgentSessionRepository sessions, AuthService auth, FeishuClient client,
                            FeishuCards cards) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.orchestrator = orchestrator;
        this.sessions = sessions;
        this.auth = auth;
        this.client = client;
        this.cards = cards;
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }

    /** 事件线程只做「排队」，真正的 AI 调用在 worker 里，避免拖过飞书 3 秒的重推阈值。 */
    public void submit(Incoming incoming) {
        try {
            worker.execute(() -> handle(incoming));
        } catch (RejectedExecutionException exception) {
            log.warn("飞书消息队列已满，丢弃一条来自 {} 的消息", incoming.openId());
        }
    }

    void handle(Incoming incoming) {
        try {
            String text = normalize(incoming.text());
            if (text.isEmpty()) return;
            String lower = text.toLowerCase();

            if (text.startsWith("绑定") || lower.startsWith("bind")) {
                bind(incoming, text);
                return;
            }
            if (text.equals("帮助") || lower.equals("help") || text.equals("?") || text.equals("？")) {
                client.replyCard(incoming.messageId(), cards.notice("WorkBoard 能做什么",
                        "**直接用大白话跟我说就行。**\n\n"
                                + "- 记账：`昨天午饭 35 现金`、`这个月餐饮花了多少`\n"
                                + "- 任务：`明天下午三点提醒我交周报`、`今天还有什么没做`\n"
                                + "- 工时：`这周上了多少小时`\n"
                                + "- 习惯：`帮我看看戒烟几天了`\n\n"
                                + "涉及写操作（记账、改任务）会先生成一条待确认，你点开网页批准后才落库。\n\n"
                                + "发送 **我的ID** 可以查看你的飞书标识。", "green"));
                return;
            }
            if (text.equals("我的ID") || text.equals("我的id") || lower.equals("openid")) {
                client.replyCard(incoming.messageId(), cards.notice("你的飞书标识",
                        "`" + incoming.openId() + "`", "grey"));
                return;
            }

            Binding binding = binding(incoming.openId());
            if (binding == null) {
                client.replyCard(incoming.messageId(), cards.noticeWithAction("还没绑定账号",
                        "先去网页的 **设置 → 推送提醒** 里点「连接飞书」，拿到 6 位绑定码，"
                                + "然后在这里发送 `绑定 123456`。", "orange",
                        "打开设置页", "http://jsn1024.cn/settings?module=push"));
                return;
            }
            answer(incoming, binding, text);
        } catch (Exception exception) {
            log.warn("飞书消息处理失败 openId={}: {}", incoming.openId(), exception.getMessage());
            safeReply(incoming.messageId(), "处理这条消息时出错了：" + brief(exception));
        }
    }

    private void bind(Incoming incoming, String text) {
        String code = text.replaceFirst("(?i)^(绑定|bind)", "").trim();
        if (code.isEmpty()) {
            safeReply(incoming.messageId(), "把 6 位绑定码一起发过来，例如：绑定 123456");
            return;
        }
        List<Long> owners = jdbc.queryForList(
                "SELECT user_id FROM feishu_bind_code WHERE code=? AND expires_at>CURRENT_TIMESTAMP(3)",
                Long.class, code);
        if (owners.isEmpty()) {
            safeReply(incoming.messageId(), "绑定码不对或已过期，去设置页重新生成一个。");
            return;
        }
        long userId = owners.get(0);
        String sessionId = UUID.randomUUID().toString();
        sessions.create(sessionId, userId, SESSION_TITLE, false);
        jdbc.update("INSERT INTO feishu_binding(open_id,user_id,union_id,chat_id,session_id) VALUES(?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),union_id=VALUES(union_id)," +
                        "chat_id=VALUES(chat_id),session_id=VALUES(session_id),bound_at=CURRENT_TIMESTAMP(3)",
                incoming.openId(), userId, incoming.unionId(), incoming.chatId(), sessionId);
        jdbc.update("DELETE FROM feishu_bind_code WHERE user_id=?", userId);
        String name = jdbc.query("SELECT COALESCE(NULLIF(nickname,''),username) FROM app_user WHERE id=?",
                result -> result.next() ? result.getString(1) : "", userId);
        log.info("飞书绑定成功 openId={} userId={}", incoming.openId(), userId);
        client.replyCard(incoming.messageId(), cards.noticeWithAction("绑定成功",
                "已连接到 **" + name + "**。直接说话就能记账、加任务、查数据；"
                        + "写下这句话也会同步出现在网页的「AI 会话」里。", "green",
                "打开网页", "http://jsn1024.cn/agent"));
    }

    private void answer(Incoming incoming, Binding binding, String text) {
        CurrentUser user = auth.loadUser(binding.userId());
        if (user == null) {
            safeReply(incoming.messageId(), "绑定的账号已停用，去网页重新绑定。");
            return;
        }
        String sessionId = ensureSession(binding);
        String link = "http://jsn1024.cn/agent?session=" + sessionId;
        // 先占位再流式刷新：飞书没有 SSE，只能靠 PATCH 同一张卡片来「边说边显示」
        LiveCard live = openLiveCard(incoming, link);
        LlmGateway.ChatResponse response;
        try {
            response = runAs(user, () -> orchestrator.chatStreamingForUser(
                    sessionId, binding.userId(), text, new AgentOrchestrator.StreamListener() {
                        @Override
                        public void delta(String content) {
                            // 增量直接喂给卡片；节流与推送节拍由 LiveCard 自己控制
                            if (live != null) live.append(content);
                        }

                        @Override
                        public void toolStarted(String name) {
                        }

                        @Override
                        public void toolCompleted(LlmGateway.ToolExecution execution) {
                        }
                    }));
        } catch (RuntimeException exception) {
            if (live != null) live.replace(cards.notice("出了点问题", brief(exception), "red"));
            throw exception;
        }
        String content = response == null ? "" : response.content();
        if (content == null || content.isBlank()) content = "这次没想出来怎么回答，换个说法试试？";
        boolean pending = response != null && !response.actions().isEmpty();
        com.fasterxml.jackson.databind.node.ObjectNode card = pending
                ? cards.noticeWithAction("需要你确认一下",
                        content + "\n\n写操作要先批准才会生效。", "orange", "去审批", "http://jsn1024.cn/approvals")
                : cards.answer(firstLine(text), content, link);
        try {
            if (live != null) live.finish(card);
            else client.replyCard(incoming.messageId(), card);
        } finally {
            if (live != null) live.stop();
        }
        jdbc.update("UPDATE feishu_binding SET last_message_at=CURRENT_TIMESTAMP(3) WHERE open_id=?", incoming.openId());
    }

    /** 发一张「正在思考」的卡片拿到 message_id；拿不到就走一次性回复，不影响功能。 */
    private LiveCard openLiveCard(Incoming incoming, String link) {
        try {
            String cardId = client.replyCard(incoming.messageId(), cards.streaming("", link));
            if (cardId == null || cardId.isBlank()) return null;
            return new LiveCard(incoming.messageId(), cardId, link);
        } catch (Exception exception) {
            log.debug("流式占位卡片发送失败，退回一次性回复：{}", exception.getMessage());
            return null;
        }
    }

    /**
     * 一张「会长大」的卡片。
     *
     * <p>飞书单条卡片更新限 5 QPS，所以固定 900ms 一拍，把当前累积的正文推上去；
     * 帧与帧之间不排队——上一帧没回来就跳过这一拍，永远只推最新快照，省得积压。</p>
     */
    private final class LiveCard {
        private final String sourceMessageId;
        private final String messageId;
        private final String link;
        private final StringBuffer text = new StringBuffer();
        private final ScheduledExecutorService pump;
        private final Object lock = new Object();
        private volatile String pushed = "";
        private volatile boolean closed;

        LiveCard(String sourceMessageId, String messageId, String link) {
            this.sourceMessageId = sourceMessageId;
            this.messageId = messageId;
            this.link = link;
            this.pump = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "feishu-card");
                thread.setDaemon(true);
                return thread;
            });
            this.pump.scheduleWithFixedDelay(this::tick, 900, 900, TimeUnit.MILLISECONDS);
        }

        void append(String delta) {
            if (delta == null || delta.isEmpty() || closed) return;
            synchronized (text) {
                text.append(delta);
            }
        }

        private void tick() {
            if (closed) return;
            synchronized (lock) {
                if (closed) return;
                String snapshot;
                synchronized (text) {
                    snapshot = text.toString();
                }
                if (snapshot.isBlank() || snapshot.equals(pushed)) return;
                pushed = snapshot;
                try {
                    client.patchCard(messageId, cards.streaming(snapshot, link));
                } catch (Exception exception) {
                    // 限频（230020）或网络抖动只影响这一帧，下一拍会带最新文本重来
                    log.debug("卡片流式刷新失败：{}", exception.getMessage());
                }
            }
        }

        /** 用最终卡片覆盖；覆盖不上就补发一条，保证用户一定能看到回答。 */
        void finish(com.fasterxml.jackson.databind.node.ObjectNode card) {
            closed = true;
            stop();
            synchronized (lock) {
                try {
                    client.patchCard(messageId, card);
                } catch (Exception exception) {
                    log.warn("最终卡片更新失败，改为补发一条：{}", exception.getMessage());
                    try {
                        client.replyCard(sourceMessageId, card);
                    } catch (Exception fallback) {
                        log.warn("补发最终卡片也失败：{}", fallback.getMessage());
                    }
                }
            }
        }

        void replace(com.fasterxml.jackson.databind.node.ObjectNode card) {
            closed = true;
            stop();
            synchronized (lock) {
                try {
                    client.patchCard(messageId, card);
                } catch (Exception exception) {
                    log.warn("错误卡片更新失败：{}", exception.getMessage());
                }
            }
        }

        /** 幂等收尾：任何分支漏了 finish/replace 也不会留下空转的定时线程。 */
        void stop() {
            closed = true;
            pump.shutdownNow();
        }
    }

    /** 会话被网页删掉后绑定还在，这里补建一个，避免用户说话突然没反应。 */
    private String ensureSession(Binding binding) {
        OptionalLong owner = sessions.findOwner(binding.sessionId());
        if (owner.isPresent() && owner.getAsLong() == binding.userId()) return binding.sessionId();
        String sessionId = UUID.randomUUID().toString();
        sessions.create(sessionId, binding.userId(), SESSION_TITLE, false);
        jdbc.update("UPDATE feishu_binding SET session_id=? WHERE open_id=?", sessionId, binding.openId());
        return sessionId;
    }

    private <T> T runAs(CurrentUser user, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                user.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private Binding binding(String openId) {
        List<Binding> rows = jdbc.query("SELECT open_id,user_id,session_id FROM feishu_binding WHERE open_id=?",
                (row, number) -> new Binding(row.getString(1), row.getLong(2), row.getString(3)), openId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 生成绑定码：6 位数字，10 分钟有效；新码会顶掉同一账号的旧码。 */
    public String createBindCode(long userId) {
        String code = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        jdbc.update("INSERT INTO feishu_bind_code(code,user_id,expires_at) VALUES(?,?,TIMESTAMPADD(MINUTE,10,CURRENT_TIMESTAMP(3))) " +
                "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),expires_at=VALUES(expires_at),created_at=CURRENT_TIMESTAMP(3)",
                code, userId);
        return code;
    }

    public BindingView bindingOf(long userId) {
        List<BindingView> rows = jdbc.query("SELECT open_id,bound_at,last_message_at FROM feishu_binding WHERE user_id=?",
                (row, number) -> new BindingView(row.getString(1),
                        row.getTimestamp(2) == null ? null : row.getTimestamp(2).toInstant().toString(),
                        row.getTimestamp(3) == null ? null : row.getTimestamp(3).toInstant().toString()), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void unbind(long userId) {
        jdbc.update("DELETE FROM feishu_binding WHERE user_id=?", userId);
    }

    public record BindingView(String openId, String boundAt, String lastMessageAt) {
    }

    private void safeReply(String messageId, String text) {
        try {
            client.replyText(messageId, text);
        } catch (Exception exception) {
            log.warn("飞书回复失败: {}", exception.getMessage());
        }
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        // 群聊里 @机器人 会留下 @_user_1 这种占位符，去掉后再当命令解析
        String text = raw.replaceAll("@_user_\\d+", " ").replaceAll("@_all", " ").trim();
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private String firstLine(String text) {
        String value = text.replace('\n', ' ').trim();
        return value.length() <= 24 ? value : value.substring(0, 24) + "…";
    }

    private String brief(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() <= 120 ? message : message.substring(0, 120) + "…";
    }

    /** 飞书事件里我们关心的四个字段 */
    public record Incoming(String openId, String unionId, String chatId, String chatType,
                           String messageId, String text) {
    }

    private record Binding(String openId, long userId, String sessionId) {
    }

    JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("事件体不是合法 JSON");
        }
    }
}
