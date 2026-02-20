package com.simpleec.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * NanoID 生成工具（用於所有主鍵）
 *
 * 長度 20 字元，支援 URL safe 字符集
 */
public class NanoIdUtil {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SIZE = 20;

    /**
     * 生成 NanoID
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(SIZE);
        for (int i = 0; i < SIZE; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 生成帶前綴的 NanoID
     * @param prefix 前綴 (e.g., "ORD_", "PROD_")
     */
    public static String generateWithPrefix(String prefix) {
        return prefix + generate();
    }

    /**
     * 驗證是否為有效 NanoID
     */
    public static boolean isValid(String id) {
        if (id == null || id.length() != SIZE) {
            return false;
        }
        for (char c : id.toCharArray()) {
            if (ALPHABET.indexOf(c) == -1) {
                return false;
            }
        }
        return true;
    }
}
