-- CalDAV 单向导出：把任务与倒数日写进 Radicale 的日历集合。
-- 一个实体一个 .ics（UID 稳定），靠指纹判断是否需要重写，避免每轮全量 PUT。

CREATE TABLE IF NOT EXISTS caldav_sync (
    user_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    collection_path VARCHAR(200) NOT NULL DEFAULT '/workboard/workboard/',
    last_synced_at TIMESTAMP(3) NULL,
    last_event_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_caldav_sync_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS caldav_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_uid VARCHAR(120) NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'TASK / COUNTDOWN',
    fingerprint CHAR(64) NOT NULL,
    synced_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_caldav_event_uid (user_id, event_uid),
    CONSTRAINT fk_caldav_event_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
