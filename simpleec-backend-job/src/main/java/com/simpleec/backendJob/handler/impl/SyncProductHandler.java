package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.Product;
import com.simpleec.core.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * SYNC_PRODUCT handler — creates or updates a product record based on multi-channel SKU aggregation.
 *
 * Triggered by task.backend topic when a channel job determines that a product entry
 * does not yet exist for the given merchant + SKU combination.
 *
 * Upsert key: (merchant_id, sku)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncProductHandler extends AbstractEventHandler {

    private final ProductRepository productRepository;

    @Override
    public String getTaskType() {
        return "SYNC_PRODUCT";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        JsonNode body = event.get("body");

        if (body == null) {
            log.warn("SYNC_PRODUCT event missing body");
            return;
        }

        if (merchantId == null) {
            log.warn("SYNC_PRODUCT missing merchantId in header");
            return;
        }

        String sku  = body.has("sku")  ? body.get("sku").asText()  : null;
        String name = body.has("name") ? body.get("name").asText() : null;

        if (sku == null || sku.isBlank()) {
            log.warn("SYNC_PRODUCT missing sku for merchantId={}", merchantId);
            return;
        }

        BigDecimal costPrice = null;
        if (body.has("costPrice") && !body.get("costPrice").isNull()) {
            try {
                costPrice = body.get("costPrice").decimalValue();
            } catch (Exception e) {
                log.warn("SYNC_PRODUCT failed to parse costPrice: {}", body.get("costPrice").asText());
            }
        }

        BigDecimal suggestPrice = null;
        if (body.has("suggestPrice") && !body.get("suggestPrice").isNull()) {
            try {
                suggestPrice = body.get("suggestPrice").decimalValue();
            } catch (Exception e) {
                log.warn("SYNC_PRODUCT failed to parse suggestPrice: {}", body.get("suggestPrice").asText());
            }
        }

        // Upsert: look up by (merchantId, sku)
        Optional<Product> existing = productRepository.findByMerchantIdAndSku(merchantId, sku);

        Product product;
        if (existing.isPresent()) {
            // UPDATE — refresh mutable fields; do not overwrite quantity or status
            product = existing.get();
            if (name != null && !name.isBlank()) {
                product.setProductName(name);
            }
            if (costPrice != null) {
                product.setCostPrice(costPrice);
            }
            if (suggestPrice != null) {
                product.setSuggestPrice(suggestPrice);
            }
            log.info("SYNC_PRODUCT updating product id={} sku={} merchantId={}", product.getId(), sku, merchantId);
        } else {
            // INSERT
            product = Product.builder()
                    .id(NanoIdUtil.generateComposite(merchantId))
                    .merchantId(merchantId)
                    .sku(sku)
                    .productName(name != null ? name : sku)
                    .costPrice(costPrice)
                    .suggestPrice(suggestPrice)
                    .quantity(0)
                    .safetyQuantity(0)
                    .status("active")
                    .build();
            log.info("SYNC_PRODUCT inserting new product sku={} merchantId={}", sku, merchantId);
        }

        productRepository.save(product);
        log.info("SYNC_PRODUCT completed for merchantId={} sku={}", merchantId, sku);
    }
}
