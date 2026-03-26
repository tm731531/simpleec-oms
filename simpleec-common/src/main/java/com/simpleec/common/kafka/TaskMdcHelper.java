package com.simpleec.common.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.MDC;

/**
 * Sets/clears SLF4J MDC fields from a Kafka message header.
 * Usage in every Kafka consumer handle() method:
 *   TaskMdcHelper.set(message);
 *   try { ... } finally { TaskMdcHelper.clear(); }
 */
public final class TaskMdcHelper {

    public static final String KEY_MERCHANT_ID = "merchantId";
    public static final String KEY_TASK_TYPE = "taskType";
    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_PLATFORM_ID = "platformId";
    public static final String KEY_CHANNEL_ID = "channelId";

    private TaskMdcHelper() {}

    /**
     * Extracts header fields from a Kafka message JsonNode and puts them in MDC.
     * Missing or null fields are silently skipped.
     */
    public static void set(JsonNode message) {
        JsonNode header = message.path("header");
        putIfPresent(KEY_MERCHANT_ID, header.path("merchantId"));
        putIfPresent(KEY_TASK_TYPE, header.path("taskType"));
        putIfPresent(KEY_REQUEST_ID, header.path("requestId"));
        putIfPresent(KEY_PLATFORM_ID, header.path("platformId"));
        putIfPresent(KEY_CHANNEL_ID, header.path("channelId"));
    }

    /**
     * Clears all MDC fields set by this helper.
     * Always call in a finally block to prevent context leakage between messages.
     */
    public static void clear() {
        MDC.remove(KEY_MERCHANT_ID);
        MDC.remove(KEY_TASK_TYPE);
        MDC.remove(KEY_REQUEST_ID);
        MDC.remove(KEY_PLATFORM_ID);
        MDC.remove(KEY_CHANNEL_ID);
    }

    private static void putIfPresent(String key, JsonNode node) {
        if (!node.isMissingNode() && !node.isNull() && !node.asText().isEmpty()) {
            MDC.put(key, node.asText());
        }
    }
}
