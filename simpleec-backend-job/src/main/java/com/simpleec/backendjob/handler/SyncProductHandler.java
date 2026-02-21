package com.simpleec.backendjob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.entity.Product;
import com.simpleec.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 產品同步處理器
 *
 * 職責：
 * - 從 OMS 產品數據庫同步到各個通路平台
 * - 更新 SKU、價格、庫存、描述等信息
 * - 處理產品上架/下架狀態變更
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncProductHandler {

    private final ProductService productService;
    private final ObjectMapper objectMapper;

    /**
     * 同步產品到各平台
     *
     * Body 格式：
     * {
     *   "syncType": "FULL|INCREMENTAL",  // 全量或增量
     *   "merchantId": "M001",
     *   "platforms": ["shopify", "shopee", "easystore"],
     *   "productIds": ["PROD-001", "PROD-002"]  // 可選，不提供則同步所有
     * }
     */
    public void syncProducts(String merchantId, JsonNode body) throws Exception {
        try {
            String syncType = body.get("syncType").asText("INCREMENTAL");
            List<String> platforms = extractPlatforms(body);
            List<String> productIds = extractProductIds(body);

            log.info("Starting product sync - type: {}, merchant: {}, platforms: {}",
                syncType, merchantId, platforms);

            if ("FULL".equals(syncType)) {
                syncFullProducts(merchantId, platforms);
            } else {
                syncIncrementalProducts(merchantId, platforms, productIds);
            }

            log.info("Product sync completed for {}", merchantId);

        } catch (Exception e) {
            log.error("Error syncing products for {}", merchantId, e);
            throw e;
        }
    }

    /**
     * 全量同步：同步所有產品
     */
    private void syncFullProducts(String merchantId, List<String> platforms) {
        try {
            // TODO: 從 ProductService 獲取所有產品
            List<Product> allProducts = new ArrayList<>(); // productService.findByMerchantId(merchantId);

            log.info("Full sync: {} products for {} platforms", allProducts.size(), platforms.size());

            for (Product product : allProducts) {
                for (String platform : platforms) {
                    try {
                        syncProductToPlatform(product, platform, merchantId);
                    } catch (Exception e) {
                        log.error("Failed to sync product {} to {}", product.getId(), platform, e);
                        // 繼續處理其他產品和平台
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error in full product sync for {}", merchantId, e);
        }
    }

    /**
     * 增量同步：同步指定的產品
     */
    private void syncIncrementalProducts(String merchantId, List<String> platforms, List<String> productIds) {
        try {
            if (productIds == null || productIds.isEmpty()) {
                log.warn("No product IDs specified for incremental sync");
                return;
            }

            log.info("Incremental sync: {} products to {} platforms", productIds.size(), platforms.size());

            for (String productId : productIds) {
                try {
                    // TODO: 從 ProductService 獲取指定產品
                    Product product = null; // productService.findById(productId);

                    if (product != null) {
                        for (String platform : platforms) {
                            try {
                                syncProductToPlatform(product, platform, merchantId);
                            } catch (Exception e) {
                                log.error("Failed to sync product {} to {}", productId, platform, e);
                            }
                        }
                    } else {
                        log.warn("Product not found: {}", productId);
                    }
                } catch (Exception e) {
                    log.error("Error syncing product {}", productId, e);
                }
            }

        } catch (Exception e) {
            log.error("Error in incremental product sync for {}", merchantId, e);
        }
    }

    /**
     * 同步單個產品到指定平台
     *
     * 實際實現需要調用各平台的 API：
     * - Shopify: PUT /admin/api/2024-01/products/{id}.json
     * - Shopee: PUT /api/v2/product/{product_id}/
     * - Easystore: PUT /api/products/{sku}
     */
    private void syncProductToPlatform(Product product, String platform, String merchantId) {
        log.debug("Syncing product {} to platform {} for merchant {}",
            product.getId(), platform, merchantId);

        // 模擬同步：實際應調用各平台 API
        switch (platform.toLowerCase()) {
            case "shopify":
                syncToShopify(product, merchantId);
                break;
            case "shopee":
                syncToShopee(product, merchantId);
                break;
            case "easystore":
                syncToEasystore(product, merchantId);
                break;
            default:
                log.warn("Unknown platform: {}", platform);
        }
    }

    private void syncToShopify(Product product, String merchantId) {
        log.debug("Syncing to Shopify - SKU: {}, Merchant: {}", product.getSku(), merchantId);
        // TODO: 調用 ShopifyAdapter 的 updateProduct 方法
    }

    private void syncToShopee(Product product, String merchantId) {
        log.debug("Syncing to Shopee - SKU: {}, Merchant: {}", product.getSku(), merchantId);
        // TODO: 調用 ShopeeAdapter 的 updateProduct 方法
    }

    private void syncToEasystore(Product product, String merchantId) {
        log.debug("Syncing to Easystore - SKU: {}, Merchant: {}", product.getSku(), merchantId);
        // TODO: 調用 EasystoreAdapter 的 updateProduct 方法
    }

    private List<String> extractPlatforms(JsonNode body) {
        List<String> platforms = new ArrayList<>();
        if (body.has("platforms") && body.get("platforms").isArray()) {
            body.get("platforms").forEach(p -> platforms.add(p.asText()));
        }
        return platforms;
    }

    private List<String> extractProductIds(JsonNode body) {
        List<String> productIds = new ArrayList<>();
        if (body.has("productIds") && body.get("productIds").isArray()) {
            body.get("productIds").forEach(id -> productIds.add(id.asText()));
        }
        return productIds;
    }
}
