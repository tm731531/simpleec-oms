package com.simpleec.core.observability;

import com.simpleec.core.kafka.TaskMessage;
import org.slf4j.MDC;

public final class TaskMdcHelper {

    private TaskMdcHelper() {}

    public static void set(TaskMessage msg) {
        if (msg == null) return;
        putIfNotNull("messageId", msg.getMessageId());
        putIfNotNull("merchantId", msg.getMerchantId());
        putIfNotNull("taskAction", msg.getTaskAction());
        putIfNotNull("taskType", msg.getTaskType());
        putIfNotNull("sourceJobType", msg.getSourceJobType());
        putIfNotNull("businessTraceId", msg.getTraceId());
    }

    public static void clear() {
        MDC.remove("messageId");
        MDC.remove("merchantId");
        MDC.remove("taskAction");
        MDC.remove("taskType");
        MDC.remove("sourceJobType");
        MDC.remove("businessTraceId");
    }

    private static void putIfNotNull(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
