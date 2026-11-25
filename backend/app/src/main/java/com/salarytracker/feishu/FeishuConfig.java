package com.salarytracker.feishu;

import org.springframework.stereotype.Component;

/**
 * 飞书企业自建应用的部署级配置。
 *
 * <p>凭证属于「整个部署一份」，不跟着用户走，所以放环境变量而不是数据库：
 * 和 {@code PUSH_SMTP_*} 同一套做法，改完重启容器生效。
 * 用户级的差异（谁绑定了哪个账号）在 {@code feishu_binding} 表里。</p>
 */
@Component
public class FeishuConfig {
    private final String appId;
    private final String appSecret;
    private final String encryptKey;
    private final String verificationToken;

    public FeishuConfig() {
        this(System.getenv("FEISHU_APP_ID"), System.getenv("FEISHU_APP_SECRET"),
                System.getenv("FEISHU_ENCRYPT_KEY"), System.getenv("FEISHU_VERIFICATION_TOKEN"));
    }

    FeishuConfig(String appId, String appSecret, String encryptKey, String verificationToken) {
        this.appId = text(appId);
        this.appSecret = text(appSecret);
        this.encryptKey = text(encryptKey);
        this.verificationToken = text(verificationToken);
    }

    public String appId() {
        return appId;
    }

    public String appSecret() {
        return appSecret;
    }

    public String encryptKey() {
        return encryptKey;
    }

    public String verificationToken() {
        return verificationToken;
    }

    /** 能不能调用开放平台接口（发消息、换 token） */
    public boolean ready() {
        return !appId.isEmpty() && !appSecret.isEmpty();
    }

    /** 回调是否加密传输。配了 Encrypt Key 就必须解密，否则 challenge 校验拿不到值 */
    public boolean encrypted() {
        return !encryptKey.isEmpty();
    }

    /** 回调地址能否通过校验的前提：至少要有 Verification Token 用来认出来自飞书 */
    public boolean webhookReady() {
        return !verificationToken.isEmpty() || encrypted();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
