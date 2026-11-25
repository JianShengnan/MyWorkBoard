package com.salarytracker.feishu;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 飞书回调的加解密与验签。
 *
 * <p>算法固定为 AES-256-CBC：key = SHA-256(Encrypt Key)，密文 Base64 解码后前 16 字节是 IV，
 * 后面是 PKCS#7 填充的密文。这里的实现不依赖官方 SDK，避免为一个算法引入整包依赖。</p>
 */
final class FeishuCrypto {
    private static final int BLOCK = 16;

    private FeishuCrypto() {
    }

    /** 解不开就抛 {@link IllegalArgumentException}，让调用方直接返回 4xx 而不是安静地丢掉事件。 */
    static String decrypt(String encryptKey, String encrypted) {
        if (encrypted == null || encrypted.isBlank()) throw new IllegalArgumentException("加密内容为空");
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] raw = Base64.getDecoder().decode(encrypted);
            if (raw.length <= BLOCK) throw new IllegalArgumentException("加密内容长度不合法");
            byte[] iv = Arrays.copyOfRange(raw, 0, BLOCK);
            byte[] body = Arrays.copyOfRange(raw, BLOCK, raw.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(body);
            return new String(unpad(plain), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("飞书回调解密失败：" + exception.getMessage(), exception);
        }
    }

    /** 测试与本地模拟用：按同样的算法加密，保证 {@link #decrypt} 能还原。 */
    static String encrypt(String encryptKey, String plain) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] iv = new byte[BLOCK];
            new java.security.SecureRandom().nextBytes(iv);
            byte[] padded = pad(plain.getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(padded);
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception exception) {
            throw new IllegalStateException("飞书回调加密失败", exception);
        }
    }

    /**
     * 回调验签：{@code sha256(timestamp + nonce + encryptKey + body)}。
     * 未配置 Encrypt Key 时飞书不下发这组 header，直接放行由 Verification Token 兜底。
     */
    static boolean signatureValid(String timestamp, String nonce, String encryptKey, String body, String signature) {
        if (signature == null || signature.isBlank()) return false;
        String expected = sha256Hex(timestamp + nonce + encryptKey + body);
        return constantTimeEquals(expected, signature);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) builder.append(String.format("%02x", item));
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pad(byte[] input) {
        int padding = BLOCK - (input.length % BLOCK);
        byte[] out = Arrays.copyOf(input, input.length + padding);
        Arrays.fill(out, input.length, out.length, (byte) padding);
        return out;
    }

    private static byte[] unpad(byte[] input) {
        if (input.length == 0 || input.length % BLOCK != 0) {
            throw new IllegalArgumentException("密文长度不是 16 的整数倍");
        }
        int padding = input[input.length - 1] & 0xFF;
        if (padding < 1 || padding > BLOCK || padding > input.length) {
            throw new IllegalArgumentException("Encrypt Key 不匹配或密文已损坏");
        }
        // 用错 key 时 AES 不报错，只是解出乱码；把整段填充字节都对一遍，才能把这种情况挡掉
        for (int index = input.length - padding; index < input.length; index++) {
            if ((input[index] & 0xFF) != padding) {
                throw new IllegalArgumentException("Encrypt Key 不匹配或密文已损坏");
            }
        }
        return Arrays.copyOf(input, input.length - padding);
    }
}
