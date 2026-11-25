package com.salarytracker.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 飞书回调解密的回归测试：算法写错的话线上表现是「地址存不进去」，本地先钉住。 */
class FeishuCryptoTest {
    private static final String KEY = "o67Gbga4P2SHwa5BQeNLFf8Xjuoj6Oo1";

    @Test
    void 往返解密中文事件体() {
        String plain = "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"im.message.receive_v1\"},"
                + "\"event\":{\"message\":{\"content\":\"{\\\"text\\\":\\\"昨天午饭 35 现金\\\"}\"}}}";
        assertEquals(plain, FeishuCrypto.decrypt(KEY, FeishuCrypto.encrypt(KEY, plain)));
    }

    @Test
    void 往返解密刚好整块的内容() {
        // 16 字节整倍数会补满一整块 padding，去填充写错就会多出 16 个 \u0010
        String plain = "0123456789abcdef";
        assertEquals(plain, FeishuCrypto.decrypt(KEY, FeishuCrypto.encrypt(KEY, plain)));
    }

    @Test
    void 每次加密的IV不同() {
        String plain = "{\"challenge\":\"abc\"}";
        assertNotEquals(FeishuCrypto.encrypt(KEY, plain), FeishuCrypto.encrypt(KEY, plain));
    }

    @Test
    void 换错密钥解不出来() {
        String encrypted = FeishuCrypto.encrypt(KEY, "{\"challenge\":\"abc\"}");
        assertThrows(IllegalArgumentException.class,
                () -> FeishuCrypto.decrypt("M2eURqGF07epgNtPVGKQth6lHtJW3TzG", encrypted));
    }

    @Test
    void 空内容直接拒绝() {
        assertThrows(IllegalArgumentException.class, () -> FeishuCrypto.decrypt(KEY, ""));
    }

    @Test
    void 验签只接受同样的四元组() {
        String timestamp = "1732500000";
        String nonce = "a1b2c3";
        String body = "{\"encrypt\":\"xxx\"}";
        String signature = FeishuCrypto.sha256Hex(timestamp + nonce + KEY + body);
        assertTrue(FeishuCrypto.signatureValid(timestamp, nonce, KEY, body, signature));
        assertFalse(FeishuCrypto.signatureValid(timestamp, nonce, KEY, body + " ", signature));
        assertFalse(FeishuCrypto.signatureValid(timestamp, "other", KEY, body, signature));
        assertFalse(FeishuCrypto.signatureValid(timestamp, nonce, KEY, body, ""));
    }
}
