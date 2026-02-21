package com.simpleec.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeyUtilTest {

    @Test
    void testOrderHashKey_GeneratesCorrectFormat() {
        String merchantId = "M001";
        String channelId = "shopee";
        String channelOrderId = "ORD-2024-001";

        String key = RedisKeyUtil.orderHashKey(merchantId, channelId, channelOrderId);

        assertEquals("order:hash:M001:shopee:ORD-2024-001", key);
    }

    @Test
    void testReturnHashKey_GeneratesCorrectFormat() {
        String merchantId = "M001";
        String channelId = "shopee";
        String channelRefundId = "REF-2024-001";

        String key = RedisKeyUtil.returnHashKey(merchantId, channelId, channelRefundId);

        assertEquals("return:hash:M001:shopee:REF-2024-001", key);
    }

    @Test
    void testCacheKey_GeneratesCorrectFormat() {
        String type = "merchant";
        String id = "M001";

        String key = RedisKeyUtil.cacheKey(type, id);

        assertEquals("cache:merchant:M001", key);
    }

    @Test
    void testLockKey_GeneratesCorrectFormat() {
        String resource = "order:M001:ORD-2024-001";

        String key = RedisKeyUtil.lockKey(resource);

        assertEquals("lock:order:M001:ORD-2024-001", key);
    }
}
