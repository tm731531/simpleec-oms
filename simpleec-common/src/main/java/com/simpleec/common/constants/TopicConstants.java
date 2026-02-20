package com.simpleec.common.constants;

/**
 * Kafka Topic 常數定義
 */
public class TopicConstants {

    // ========== 核心 Topics ==========
    public static final String SCHEDULER = "scheduler";
    public static final String ORDER_PROCESS = "order.process";
    public static final String RETURN_PROCESS = "return.process";
    public static final String TASK_BACKEND = "task.backend";
    public static final String TASK_FAILED = "task.failed";
    public static final String TASK_DLT = "task.dlt";

    // ========== 動態生成 (Platform Topics) ==========
    public static String platformSlowTopic(String platformCode) {
        return platformCode.toLowerCase() + ".slow";
    }

    public static String platformFastTopic(String platformCode) {
        return platformCode.toLowerCase() + ".fast";
    }

    public static String getPlatformDetailTopic(String platformCode) {
        return platformCode.toLowerCase() + ".detail";
    }

    // ========== Topic 配置 ==========
    public static final int DEFAULT_PARTITIONS = 8;
    public static final int HIGH_VOLUME_PARTITIONS = 16;
    public static final int LOW_VOLUME_PARTITIONS = 4;
    public static final int SINGLE_PARTITION = 1;
}
