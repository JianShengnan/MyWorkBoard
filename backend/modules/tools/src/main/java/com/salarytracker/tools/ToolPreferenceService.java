package com.salarytracker.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.platform.ConflictException;
import com.salarytracker.tools.ToolsModels.ToolPreference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 工具箱「上次填的参数」按用户持久化。
 * <p>
 * 房贷与个税本身是纯前端计算，后端只保存一份输入 JSON：换设备、清缓存后
 * 再次打开仍是自己上次算的那套数。工具键走白名单，避免这张表变成任意 KV 仓库。
 */
@Service
public class ToolPreferenceService {
    /** 允许持久化参数的工具；新增工具时在这里登记即可 */
    private static final Set<String> SUPPORTED_TOOLS = Set.of("mortgage", "tax");
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;

    private final JdbcTemplate jdbc;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper objectMapper;

    public ToolPreferenceService(JdbcTemplate jdbc, CurrentUserResolver currentUser, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    public ToolPreference read(String tool) {
        String key = toolKey(tool);
        long userId = currentUser.id();
        List<ToolPreference> rows = jdbc.query(
                "SELECT tool_key,payload,revision FROM tool_preference WHERE user_id=? AND tool_key=?",
                (row, index) -> new ToolPreference(row.getString("tool_key"), payload(row.getString("payload")),
                        row.getLong("revision")),
                userId, key);
        return rows.isEmpty() ? new ToolPreference(key, null, 0) : rows.get(0);
    }

    @Transactional
    public ToolPreference write(String tool, JsonNode body, String ifMatch) {
        String key = toolKey(tool);
        long userId = currentUser.id();
        if (body == null || !body.isObject()) throw new IllegalArgumentException("参数必须是一个 JSON 对象");
        String payload = body.toString();
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("参数体积超出限制");
        }
        Long expected = revision(ifMatch);
        List<Long> revisions = jdbc.query("SELECT revision FROM tool_preference WHERE user_id=? AND tool_key=?",
                (row, index) -> row.getLong("revision"), userId, key);
        if (revisions.isEmpty()) {
            jdbc.update("INSERT INTO tool_preference(public_id,user_id,tool_key,payload) VALUES(?,?,?,?)",
                    UUID.randomUUID().toString(), userId, key, payload);
        } else {
            long current = revisions.get(0);
            if (expected != null && expected != current) {
                throw new ConflictException("参数版本已变化", current);
            }
            jdbc.update("UPDATE tool_preference SET payload=?,revision=revision+1 WHERE user_id=? AND tool_key=?",
                    payload, userId, key);
        }
        return read(key);
    }

    private String toolKey(String tool) {
        String key = tool == null ? "" : tool.trim().toLowerCase();
        if (!SUPPORTED_TOOLS.contains(key)) throw new IllegalArgumentException("不支持的工具：" + tool);
        return key;
    }

    private JsonNode payload(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException("参数内容不是合法 JSON", exception);
        }
    }

    private Long revision(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) return null;
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match 必须是 revision");
        }
    }
}
