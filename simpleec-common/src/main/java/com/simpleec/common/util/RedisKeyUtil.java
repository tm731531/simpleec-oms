package com.simpleec.common.util;

/**
 * Redis Key 生成工具
 */
public class RedisKeyUtil {

    private static final String SEPARATOR = ":";

    /**
     * 訂單 Hash Key
     * order:hash:{merchantId}:{channelId}:{channelOrderId}
     */
    public static String orderHashKey(String merchantId, String channelId, String channelOrderId) {
        return join("order", "hash", merchantId, channelId, channelOrderId);
    }

    /**
     * 退貨 Hash Key
     * return:hash:{merchantId}:{channelId}:{channelReturnId}
     */
    public static String returnHashKey(String merchantId, String channelId, String channelReturnId) {
        return join("return", "hash", merchantId, channelId, channelReturnId);
    }

    /**
     * 緩存 Key (通用)
     * cache:{type}:{id}
     */
    public static String cacheKey(String type, String id) {
        return join("cache", type, id);
    }

    /**
     * 鎖 Key (分佈式鎖)
     * lock:{resource}
     */
    public static String lockKey(String resource) {
        return join("lock", resource);
    }

    /**
     * 拼接 Key
     */
    private static String join(String... parts) {
        return String.join(SEPARATOR, parts);
    }
}
