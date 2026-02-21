package com.simpleec.backendJob.handler;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 事件處理器基礎介面
 *
 * 所有報表生成器和系統健檢都實現此介面
 * 根據 taskType 動態路由事件到相應的處理器
 */
public interface EventHandler {

    /**
     * 處理事件
     * @param event 完整的 Kafka 消息 (包含 header 和 body)
     */
    void handle(JsonNode event);

    /**
     * 獲取此處理器支援的 taskType
     * @return taskType 列舉（如 ORDER_REPORT、INVENTORY_REPORT 等）
     */
    String getTaskType();
}
