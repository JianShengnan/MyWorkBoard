-- 飞书企业自建应用：把「站外向站内推送」升级成「双向对话」
-- 一个飞书用户（open_id）绑定一个站内账号，并固定复用一个 AI 会话，聊天记录能在网页里接着看

CREATE TABLE IF NOT EXISTS feishu_binding (
    open_id VARCHAR(64) NOT NULL COMMENT '飞书用户在本应用下的唯一标识',
    user_id BIGINT NOT NULL,
    union_id VARCHAR(64) NULL,
    chat_id VARCHAR(64) NULL COMMENT '最近一次私聊的会话 id，主动推送时用',
    session_id CHAR(36) NOT NULL COMMENT '与网页共用的 agent_session',
    bound_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_message_at TIMESTAMP(3) NULL,
    PRIMARY KEY (open_id),
    KEY idx_feishu_binding_user (user_id),
    CONSTRAINT fk_feishu_binding_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS feishu_bind_code (
    code CHAR(6) NOT NULL,
    user_id BIGINT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (code),
    KEY idx_feishu_bind_code_user (user_id),
    CONSTRAINT fk_feishu_bind_code_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 飞书事件会重推（超时或 5xx），按 event_id 去重，避免一句话触发两轮 AI
CREATE TABLE IF NOT EXISTS feishu_event (
    event_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_feishu_event_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 卡片按钮回传的动作记录，便于排查「点了没反应」
CREATE TABLE IF NOT EXISTS feishu_card_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    open_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    payload VARCHAR(500) NULL,
    result VARCHAR(200) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_feishu_card_action_open (open_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
