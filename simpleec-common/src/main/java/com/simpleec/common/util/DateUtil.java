package com.simpleec.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 日期工具（處理各平台時間格式轉換）
 */
public class DateUtil {

    private static final DateTimeFormatter ISO8601 = DateTimeFormatter.ISO_DATE_TIME;
    private static final ZoneId ZONE_UTC = ZoneId.of("UTC");
    private static final ZoneId ZONE_TAIPEI = ZoneId.of("Asia/Taipei");

    /**
     * 轉換為 OMS 標準格式 (ISO-8601)
     */
    public static String toIsoString(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZONE_UTC).format(ISO8601);
    }

    /**
     * 從 ISO-8601 字符串解析
     */
    public static long fromIsoString(String iso8601) {
        return LocalDateTime.parse(iso8601, ISO8601)
            .atZone(ZONE_UTC)
            .toInstant()
            .toEpochMilli();
    }

    /**
     * 獲取當前時間戳 (ISO-8601)
     */
    public static String now() {
        return Instant.now().atZone(ZONE_UTC).format(ISO8601);
    }

    /**
     * Unix timestamp (秒) → ISO-8601
     */
    public static String fromUnixSeconds(long seconds) {
        return Instant.ofEpochSecond(seconds).atZone(ZONE_UTC).format(ISO8601);
    }

    /**
     * 轉換為台灣時區
     */
    public static String toTaipeiTime(String iso8601) {
        LocalDateTime ldt = LocalDateTime.parse(iso8601, ISO8601);
        return ldt.atZone(ZONE_UTC).withZoneSameInstant(ZONE_TAIPEI).format(ISO8601);
    }
}
