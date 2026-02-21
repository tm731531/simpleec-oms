package com.simpleec.backendJob.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/**
 * 事件處理器註冊表
 *
 * 自動發現並註冊所有實現 EventHandler 的 Bean
 * 根據 taskType 動態查詢和執行相應的處理器
 */
@Slf4j
@Component
public class EventHandlerRegistry {

    private final Map<String, EventHandler> handlers = new HashMap<>();

    /**
     * 註冊事件處理器
     * 在 Spring 啟動時自動調用（通過構造器注入）
     */
    public EventHandlerRegistry(java.util.List<EventHandler> handlerList) {
        for (EventHandler handler : handlerList) {
            String taskType = handler.getTaskType();
            handlers.put(taskType, handler);
            log.info("Registered event handler for taskType: {}", taskType);
        }
        log.info("Event handler registry initialized with {} handlers", handlers.size());
    }

    /**
     * 根據 taskType 獲取處理器
     * @param taskType 任務類型（如 ORDER_REPORT、INVENTORY_REPORT 等）
     * @return 對應的事件處理器，如果不存在返回 null
     */
    public EventHandler getHandler(String taskType) {
        return handlers.get(taskType);
    }

    /**
     * 檢查是否有指定 taskType 的處理器
     */
    public boolean hasHandler(String taskType) {
        return handlers.containsKey(taskType);
    }

    /**
     * 獲取所有已註冊的 taskType
     */
    public java.util.Set<String> getRegisteredTaskTypes() {
        return handlers.keySet();
    }
}
