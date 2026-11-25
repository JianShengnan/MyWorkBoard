-- 站外推送：任务提醒 / 检查项提醒 / 习惯未打卡汇总走飞书、Bark、邮件
-- 站内收件箱（task_inbox_message）保持不变，推送是在它之外多发一条

CREATE TABLE IF NOT EXISTS push_channel (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'FEISHU / BARK / EMAIL',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config JSON NOT NULL COMMENT '飞书 webhook+secret、Bark server+key、邮件 to',
    last_test_at TIMESTAMP(3) NULL,
    last_test_ok BOOLEAN NULL,
    last_error VARCHAR(500) NULL,
    last_sent_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_channel_public (public_id),
    UNIQUE KEY uk_push_channel_user_kind (user_id, kind),
    CONSTRAINT fk_push_channel_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS push_preference (
    user_id BIGINT NOT NULL,
    task_reminder BOOLEAN NOT NULL DEFAULT TRUE,
    checklist_reminder BOOLEAN NOT NULL DEFAULT TRUE,
    habit_digest BOOLEAN NOT NULL DEFAULT TRUE,
    habit_digest_hour INT NOT NULL DEFAULT 21,
    habit_last_sent_on DATE NULL,
    quiet_from CHAR(5) NULL,
    quiet_to CHAR(5) NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_push_preference_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS push_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    event VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'SENT / FAILED / SKIPPED',
    title VARCHAR(200) NOT NULL DEFAULT '',
    detail VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_push_delivery_user (user_id, created_at),
    CONSTRAINT fk_push_delivery_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
