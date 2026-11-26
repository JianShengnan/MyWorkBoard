-- 周报/月报：AI 总结缓存 + 外部数据（由 WorkBuddy 连接器补充）
-- 总结缓存避免每次打开页面都调一次模型；外部数据按周期存一份 JSON，页面直接渲染。

CREATE TABLE IF NOT EXISTS report_summary (
    user_id BIGINT NOT NULL,
    period_type VARCHAR(8) NOT NULL COMMENT 'WEEK / MONTH',
    period_start DATE NOT NULL,
    content TEXT NOT NULL,
    model VARCHAR(64) NULL,
    generated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, period_type, period_start),
    CONSTRAINT fk_report_summary_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS report_external (
    user_id BIGINT NOT NULL,
    period_type VARCHAR(8) NOT NULL,
    period_start DATE NOT NULL,
    payload JSON NOT NULL COMMENT '[{key,title,text,items:[{title,meta}]}]',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, period_type, period_start),
    CONSTRAINT fk_report_external_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
