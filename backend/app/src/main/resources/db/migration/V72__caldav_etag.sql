-- 双向同步：记录远端 etag，用来判断「客户端改过」而不是「我们自己刚推的」。
ALTER TABLE caldav_event ADD COLUMN etag VARCHAR(120) NULL AFTER fingerprint;
