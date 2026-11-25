-- 飞书日历同步
--
-- 飞书不支持通过 ICS 网址订阅外部日历（「订阅日历」只能搜飞书内的联系人和公共日历，
-- 「添加第三方日历」只认 Google/Exchange/Outlook 账号），所以想让任务出现在飞书日历里，
-- 只能由应用调用日历 API 主动写日程。
--
-- 这里记录每个用户同步到哪个飞书日历，以及「本地实体 -> 飞书日程」的映射。
-- 映射表存 event_id 与内容指纹：指纹没变就不调 API，改过的才 PATCH，本地删掉的才 DELETE。
CREATE TABLE feishu_calendar_sync (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    calendar_id VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    include_tasks BOOLEAN NOT NULL DEFAULT TRUE,
    include_countdowns BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at DATETIME(3) NULL,
    last_event_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fcs_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE feishu_calendar_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    feishu_event_id VARCHAR(160) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    synced_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fce_user_key (user_id, event_key),
    KEY idx_fce_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
