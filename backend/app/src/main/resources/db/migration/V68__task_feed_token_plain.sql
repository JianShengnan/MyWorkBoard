-- 日历订阅：保存可回显的订阅地址
--
-- 之前只存 token 的 SHA-256，原文只在生成时返回一次，用户刷新页面后就再也看不到地址，
-- 想复制只能「重新生成」（旧地址立即失效）。改为同时保存明文，供设置页回显。
--
-- 取舍：token 只能只读访问本人日历（任务/倒数日/习惯/工时/账目），而数据库里本就存有
-- 全部业务数据，明文 token 不增加实质泄露面；hash 仍用于订阅请求的比对。
ALTER TABLE task_feed_token
    ADD COLUMN token_value VARCHAR(64) NULL AFTER token_hash;
