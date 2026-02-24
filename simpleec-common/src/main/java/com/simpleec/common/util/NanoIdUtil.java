package com.simpleec.common.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * NanoID 生成工具（用於所有主鍵）
 *
 * 支援兩種格式：
 * 1. 簡單 20 字元隨機碼
 * 2. Composite NanoID：merchant_first_4_digits + yyyymmddhhmmss + random_code(2) = 20位
 *
 * URL safe 字符集：A-Z, a-z, 0-9, _, -
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

    /**
     * 生成 Composite NanoID（推薦用於所有業務表主鍵）
     *
     * 格式：merchant_first_4_digits + yyyymmddhhmmss + random_code(2)
     * 範例：M12320260225144530AB
     * 總長度：4 + 14 + 2 = 20 位
     *
     * 設計優勢：
     * • merchant_first_4_digits：實現商家級數據隔離
     * • yyyymmddhhmmss：支援時間排序
     * • random_code(2)：避免時間精度相同導致的碰撞
     *
     * @param merchantId 商家 ID（例如 "M123" 或 "MERCHANT_ABC"）
     * @return 20 位 Composite NanoID
     */
    public static String generateComposite(String merchantId) {
        // 1. 取商家 ID 的前 4 位
        String merchantPrefix = merchantId.substring(0, Math.min(4, merchantId.length()));
        if (merchantPrefix.length() < 4) {
            // 如果商家 ID 少於 4 位，用零填充
            merchantPrefix = String.format("%-4s", merchantPrefix).replace(' ', '0');
        }

        // 2. 獲取當前時間 yyyymmddhhmmss (14位)
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String timestamp = now.format(formatter);

        // 3. 生成 2 位隨機碼
        StringBuilder randomCode = new StringBuilder(2);
        for (int i = 0; i < 2; i++) {
            randomCode.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }

        return merchantPrefix + timestamp + randomCode.toString();
    }

    /**
     * 驗證 Composite NanoID 格式
     *
     * @param id 要驗證的 ID
     * @return 是否為有效的 Composite NanoID（20 位，yyyymmddhhmmss 為有效日期）
     */
    public static boolean isValidComposite(String id) {
        if (id == null || id.length() != SIZE) {
            return false;
        }

        // 驗證所有字符都在允許的字符集內
        for (char c : id.toCharArray()) {
            if (ALPHABET.indexOf(c) == -1) {
                return false;
            }
        }

        // 驗證時間部分（位置 4-17，yyyymmddhhmmss 格式）
        try {
            String timestampPart = id.substring(4, 18);
            int year = Integer.parseInt(timestampPart.substring(0, 4));
            int month = Integer.parseInt(timestampPart.substring(4, 6));
            int day = Integer.parseInt(timestampPart.substring(6, 8));
            int hour = Integer.parseInt(timestampPart.substring(8, 10));
            int minute = Integer.parseInt(timestampPart.substring(10, 12));
            int second = Integer.parseInt(timestampPart.substring(12, 14));

            // 基本範圍檢查
            if (month < 1 || month > 12 || day < 1 || day > 31 ||
                hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
                return false;
            }

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
