package com.simpleec.backendjob.consumer;

import com.simpleec.backendjob.handler.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Backend Job Consumer
 *
 * 消費 task.backend topic 中的各種後端任務：
 * - SYNC_PRODUCT: 產品同步到各平台
 * - SYNC_PACK: 庫存/SellerPack 同步
 * - ORDER_REPORT: 訂單報表生成
 * - INVENTORY_REPORT: 庫存報表
 * - SALES_REPORT: 銷售報表
 * - RETURN_REPORT: 退貨報表
 * - DAILY_REPORT: 日報表
 * - KAFKA_HEALTH_CHECK: 系統健康檢查
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackendJobConsumer {

    private final SyncProductHandler syncProductHandler;
    private final SyncPackHandler syncPackHandler;
    private final OrderReportHandler orderReportHandler;
    private final InventoryReportHandler inventoryReportHandler;
    private final SalesReportHandler salesReportHandler;
    private final ReturnReportHandler returnReportHandler;

    /**
     * 消費 task.backend topic
     */
    @KafkaListener(topics = "task.backend", groupId = "backend-job-group", concurrency = "4")
    public void consumeBackendTask(String message) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode json = objectMapper.readTree(message);
            JsonNode header = json.get("header");
            JsonNode body = json.get("body");

            String taskType = header.get("taskType").asText();
            String merchantId = header.get("merchantId").asText();

            log.info("Processing backend task: {} for merchant: {}", taskType, merchantId);

            // 根據 taskType 路由
            switch (taskType) {
                case "SYNC_PRODUCT":
                    handleSyncProduct(merchantId, body);
                    break;

                case "SYNC_PACK":
                    handleSyncPack(merchantId, body);
                    break;

                case "ORDER_REPORT":
                    handleOrderReport(merchantId, body);
                    break;

                case "INVENTORY_REPORT":
                    handleInventoryReport(merchantId, body);
                    break;

                case "SALES_REPORT":
                    handleSalesReport(merchantId, body);
                    break;

                case "RETURN_REPORT":
                    handleReturnReport(merchantId, body);
                    break;

                case "DAILY_REPORT":
                    handleDailyReport(merchantId, body);
                    break;

                case "KAFKA_HEALTH_CHECK":
                    handleHealthCheck(merchantId, body);
                    break;

                default:
                    log.warn("Unknown backend taskType: {}", taskType);
            }

        } catch (Exception e) {
            log.error("Error processing backend task", e);
        }
    }

    private void handleSyncProduct(String merchantId, JsonNode body) {
        try {
            syncProductHandler.syncProducts(merchantId, body);
        } catch (Exception e) {
            log.error("Error in SYNC_PRODUCT for {}", merchantId, e);
        }
    }

    private void handleSyncPack(String merchantId, JsonNode body) {
        try {
            syncPackHandler.syncPacks(merchantId, body);
        } catch (Exception e) {
            log.error("Error in SYNC_PACK for {}", merchantId, e);
        }
    }

    private void handleOrderReport(String merchantId, JsonNode body) {
        try {
            orderReportHandler.generateOrderReport(merchantId, body);
        } catch (Exception e) {
            log.error("Error in ORDER_REPORT for {}", merchantId, e);
        }
    }

    private void handleInventoryReport(String merchantId, JsonNode body) {
        try {
            inventoryReportHandler.generateInventoryReport(merchantId, body);
        } catch (Exception e) {
            log.error("Error in INVENTORY_REPORT for {}", merchantId, e);
        }
    }

    private void handleSalesReport(String merchantId, JsonNode body) {
        try {
            salesReportHandler.generateSalesReport(merchantId, body);
        } catch (Exception e) {
            log.error("Error in SALES_REPORT for {}", merchantId, e);
        }
    }

    private void handleReturnReport(String merchantId, JsonNode body) {
        try {
            returnReportHandler.generateReturnReport(merchantId, body);
        } catch (Exception e) {
            log.error("Error in RETURN_REPORT for {}", merchantId, e);
        }
    }

    private void handleDailyReport(String merchantId, JsonNode body) {
        try {
            log.info("Generating daily consolidated report for {}", merchantId);
            // 調用所有報表生成器以生成統合日報表
            orderReportHandler.generateOrderReport(merchantId, body);
            inventoryReportHandler.generateInventoryReport(merchantId, body);
            salesReportHandler.generateSalesReport(merchantId, body);
            returnReportHandler.generateReturnReport(merchantId, body);
            log.info("Daily report completed for {}", merchantId);
        } catch (Exception e) {
            log.error("Error in DAILY_REPORT for {}", merchantId, e);
        }
    }

    private void handleHealthCheck(String merchantId, JsonNode body) {
        try {
            log.info("Kafka health check - system is operational for {}", merchantId);
            // 這是一個簡單的健康檢查信號，僅記錄日誌
            // 實際上可以擴展為檢查所有 broker、topic 和 consumer 狀態
        } catch (Exception e) {
            log.error("Error in KAFKA_HEALTH_CHECK for {}", merchantId, e);
        }
    }
}
