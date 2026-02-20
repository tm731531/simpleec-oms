package com.simpleec.backendjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SellerPack/庫存同步處理器
 *
 * 職責：
 * - 同步 OMS 庫存到各平台
 * - 管理 SellerPack（跨境商品包）
 * - 更新庫存數量、預留數量、可銷售數量
 * - 處理庫存不足警告
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncPackHandler {

    private final ProductService productService;
    private final ObjectMapper objectMapper;

    /**
     * 同步庫存/SellerPack 到各平台
     *
     * Body 格式：
     * {
     *   "syncType": "INVENTORY|SELLER_PACK",
     *   "merchantId": "M001",
     *   "platforms": ["shopify", "shopee"],
     *   "warehouseId": "WH-001"  // 可選
     * }
     */
    public void syncPacks(String merchantId, JsonNode body) throws Exception {
        try {
            String syncType = body.get("syncType").asText("INVENTORY");
            List<String> platforms = extractPlatforms(body);
            String warehouseId = body.get("warehouseId").asText(null);

            log.info("Starting pack sync - type: {}, merchant: {}, warehouse: {}",
                syncType, merchantId, warehouseId);

            if ("SELLER_PACK".equals(syncType)) {
                syncSellerPacks(merchantId, platforms, warehouseId);
            } else {
                syncInventory(merchantId, platforms, warehouseId);
            }

            log.info("Pack sync completed for {}", merchantId);

        } catch (Exception e) {
            log.error("Error syncing packs for {}", merchantId, e);
            throw e;
        }
    }

    /**
     * 同步庫存數量到各平台
     */
    private void syncInventory(String merchantId, List<String> platforms, String warehouseId) {
        try {
            log.info("Syncing inventory to {} platforms", platforms.size());

            // TODO: 從 ProductService 獲取所有庫存信息
            // List<InventoryView> inventoryList = productService.getInventoryByWarehouse(merchantId, warehouseId);

            // 模擬數據
            List<InventoryData> inventoryList = generateMockInventory();

            for (InventoryData inventory : inventoryList) {
                for (String platform : platforms) {
                    try {
                        syncInventoryToPlatform(inventory, platform, merchantId);
                    } catch (Exception e) {
                        log.error("Failed to sync inventory for {} to {}",
                            inventory.getSku(), platform, e);
                    }
                }
            }

            log.info("Synced {} inventory records", inventoryList.size());

        } catch (Exception e) {
            log.error("Error syncing inventory for {}", merchantId, e);
        }
    }

    /**
     * 同步 SellerPack（跨境商品包）
     * SellerPack 用於將多個 SKU 組合成一個銷售單位
     */
    private void syncSellerPacks(String merchantId, List<String> platforms, String warehouseId) {
        try {
            log.info("Syncing SellerPacks to {} platforms", platforms.size());

            // TODO: 從服務獲取 SellerPack 列表
            // List<SellerPack> sellerPacks = sellerPackService.findByMerchant(merchantId);

            // 模擬數據
            List<SellerPackData> sellerPacks = generateMockSellerPacks();

            for (SellerPackData pack : sellerPacks) {
                for (String platform : platforms) {
                    try {
                        syncSellerPackToPlatform(pack, platform, merchantId);
                    } catch (Exception e) {
                        log.error("Failed to sync SellerPack {} to {}",
                            pack.getPackId(), platform, e);
                    }
                }
            }

            log.info("Synced {} SellerPacks", sellerPacks.size());

        } catch (Exception e) {
            log.error("Error syncing SellerPacks for {}", merchantId, e);
        }
    }

    /**
     * 同步單個庫存到指定平台
     */
    private void syncInventoryToPlatform(InventoryData inventory, String platform, String merchantId) {
        log.debug("Syncing inventory for SKU {} to platform {} (qty: {})",
            inventory.getSku(), platform, inventory.getAvailableQuantity());

        // 模擬同步：實際應調用各平台 API
        switch (platform.toLowerCase()) {
            case "shopify":
                syncInventoryToShopify(inventory, merchantId);
                break;
            case "shopee":
                syncInventoryToShopee(inventory, merchantId);
                break;
            case "easystore":
                syncInventoryToEasystore(inventory, merchantId);
                break;
            default:
                log.warn("Unknown platform: {}", platform);
        }

        // 檢查庫存不足警告
        if (inventory.getAvailableQuantity() < inventory.getLowStockThreshold()) {
            log.warn("Low stock alert - SKU: {}, Available: {}, Threshold: {}",
                inventory.getSku(), inventory.getAvailableQuantity(), inventory.getLowStockThreshold());
        }
    }

    /**
     * 同步單個 SellerPack 到指定平台
     */
    private void syncSellerPackToPlatform(SellerPackData pack, String platform, String merchantId) {
        log.debug("Syncing SellerPack {} to platform {} (items: {})",
            pack.getPackId(), platform, pack.getItems().size());

        // 模擬同步：實際應調用各平台 API
        switch (platform.toLowerCase()) {
            case "shopify":
                log.debug("Syncing SellerPack to Shopify bundle");
                break;
            case "shopee":
                log.debug("Syncing SellerPack to Shopee combo");
                break;
            case "easystore":
                log.debug("Syncing SellerPack to Easystore bundle");
                break;
        }
    }

    private void syncInventoryToShopify(InventoryData inventory, String merchantId) {
        // TODO: 調用 Shopify Inventory API
    }

    private void syncInventoryToShopee(InventoryData inventory, String merchantId) {
        // TODO: 調用 Shopee Inventory API
    }

    private void syncInventoryToEasystore(InventoryData inventory, String merchantId) {
        // TODO: 調用 Easystore Inventory API
    }

    private List<String> extractPlatforms(JsonNode body) {
        List<String> platforms = new ArrayList<>();
        if (body.has("platforms") && body.get("platforms").isArray()) {
            body.get("platforms").forEach(p -> platforms.add(p.asText()));
        }
        return platforms;
    }

    // 模擬數據結構

    private List<InventoryData> generateMockInventory() {
        List<InventoryData> inventory = new ArrayList<>();
        inventory.add(new InventoryData("PROD-001", 100, 150, 10));
        inventory.add(new InventoryData("PROD-002", 50, 75, 10));
        inventory.add(new InventoryData("PROD-003", 5, 80, 10));  // Low stock
        return inventory;
    }

    private List<SellerPackData> generateMockSellerPacks() {
        List<SellerPackData> packs = new ArrayList<>();
        SellerPackData pack1 = new SellerPackData("PACK-001", "組合套裝");
        pack1.addItem("PROD-001", 2);
        pack1.addItem("PROD-002", 1);
        packs.add(pack1);
        return packs;
    }

    // 內部數據類
    static class InventoryData {
        private String sku;
        private int reservedQuantity;
        private int totalQuantity;
        private int lowStockThreshold;

        public InventoryData(String sku, int reserved, int total, int threshold) {
            this.sku = sku;
            this.reservedQuantity = reserved;
            this.totalQuantity = total;
            this.lowStockThreshold = threshold;
        }

        public String getSku() { return sku; }
        public int getReservedQuantity() { return reservedQuantity; }
        public int getTotalQuantity() { return totalQuantity; }
        public int getAvailableQuantity() { return totalQuantity - reservedQuantity; }
        public int getLowStockThreshold() { return lowStockThreshold; }
    }

    static class SellerPackData {
        private String packId;
        private String packName;
        private List<PackItem> items = new ArrayList<>();

        public SellerPackData(String packId, String packName) {
            this.packId = packId;
            this.packName = packName;
        }

        public void addItem(String sku, int quantity) {
            items.add(new PackItem(sku, quantity));
        }

        public String getPackId() { return packId; }
        public String getPackName() { return packName; }
        public List<PackItem> getItems() { return items; }
    }

    static class PackItem {
        private String sku;
        private int quantity;

        public PackItem(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }

        public String getSku() { return sku; }
        public int getQuantity() { return quantity; }
    }
}
