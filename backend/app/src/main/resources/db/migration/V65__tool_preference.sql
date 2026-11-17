-- 工具箱参数按用户持久化（房贷测算、个税测算）
-- 设计说明：
-- 1) 这两个工具是纯前端计算，需要的只是「上次填的参数」，因此统一存成 JSON，
--    不为一堆输入框逐列建表；后续新增工具只要换 tool_key，不用再加迁移。
-- 2) 每用户每个工具一行，(user_id, tool_key) 唯一，写入沿用 revision 乐观锁。
CREATE TABLE tool_preference (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    tool_key VARCHAR(32) NOT NULL,
    payload JSON NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_preference_public (public_id),
    UNIQUE KEY uk_tool_preference_user_tool (user_id, tool_key),
    CONSTRAINT fk_tool_preference_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
