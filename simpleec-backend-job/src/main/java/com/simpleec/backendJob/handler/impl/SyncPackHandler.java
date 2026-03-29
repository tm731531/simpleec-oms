package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.Product;
import com.simpleec.core.entity.SellPack;
import com.simpleec.core.repository.ProductRepository;
import com.simpleec.core.repository.SellPackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * SYNC_PACK handler — writes channel pack data into sell_pack table.
 *
 * Triggered by task.backend topic when a channel job dispatches a SYNC_PACK event
 * after confirming the corresponding product already exists.
 *
 * Upsert key: (channel_id, channel_product_id, channel_spec_id)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncPackHandler extends AbstractEventHandler {

    private final SellPackRepository sellPackRepository;
    private final ProductRepository productRepository;

    @Override
    public String getTaskType() {
        return "SYNC_PACK";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        JsonNode header = event.get("header");
        JsonNode body = event.get("body");

        if (header == null || body == null) {
            log.warn("SYNC_PACK event missing header or body");
            return;
        }

        String channelId = header.has("channelId") ? header.get("channelId").asText() : null;

        if (merchantId == null || channelId == null) {
            log.warn("SYNC_PACK missing required header fields: merchantId={}, channelId={}", merchantId, channelId);
            return;
        }

        String channelProductId = body.has("channelProductId") ? body.get("channelProductId").asText() : null;
        String channelSpecId    = body.has("channelSpecId") && !body.get("channelSpecId").isNull()
                ? body.get("channelSpecId").asText() : null;
        String sku              = body.has("sku") ? body.get("sku").asText() : null;
        String channelProductName = body.has("channelProductName") ? body.get("channelProductName").asText() : null;
        String channelSpecName    = body.has("channelSpecName")    ? body.get("channelSpecName").asText()    : null;

        BigDecimal sellingPrice = null;
        if (body.has("sellingPrice") && !body.get("sellingPrice").isNull()) {
            try {
                sellingPrice = body.get("sellingPrice").decimalValue();
            } catch (Exception e) {
                log.warn("SYNC_PACK failed to parse sellingPrice: {}", body.get("sellingPrice").asText());
            }
        }

        String packStatus  = "draft";
        String visibility  = null;
        Integer quantity   = null;
        if (body.has("packInfo") && !body.get("packInfo").isNull()) {
            JsonNode packInfo = body.get("packInfo");
            if (packInfo.has("packStatus")) {
                packStatus = packInfo.get("packStatus").asText().toLowerCase();
            }
            if (packInfo.has("visibility")) {
                visibility = packInfo.get("visibility").asText();
            }
            if (packInfo.has("quantity") && !packInfo.get("quantity").isNull()) {
                quantity = packInfo.get("quantity").asInt();
            }
        }

        if (channelProductId == null) {
            log.warn("SYNC_PACK missing channelProductId for merchantId={} channelId={}", merchantId, channelId);
            return;
        }

        // Resolve product_id via SKU; auto-create product if first time seeing this SKU
        String productId = null;
        if (sku == null || sku.isBlank()) {
            log.warn("SYNC_PACK missing sku for merchantId={} channelId={} channelProductId={} — cannot resolve product",
                    merchantId, channelId, channelProductId);
            return;
        }

        Optional<Product> productOpt = productRepository.findByMerchantIdAndSku(merchantId, sku);
        if (productOpt.isPresent()) {
            productId = productOpt.get().getId();
        } else {
            // First time this SKU is seen — create a minimal product record.
            // sell_pack carries channel-specific pricing; product holds master data.
            Product newProduct = Product.builder()
                    .id(NanoIdUtil.generateComposite(merchantId))
                    .merchantId(merchantId)
                    .sku(sku)
                    .productName(channelProductName != null ? channelProductName : sku)
                    .suggestPrice(sellingPrice)
                    .quantity(0)
                    .safetyQuantity(0)
                    .status("active")
                    .build();
            productRepository.save(newProduct);
            productId = newProduct.getId();
            log.info("SYNC_PACK auto-created product for merchantId={} sku={} id={}", merchantId, sku, productId);
        }

        // Upsert: look up by (channelId, channelProductId, channelSpecId)
        Optional<SellPack> existing = sellPackRepository
                .findByChannelIdAndChannelProductIdAndChannelSpecId(channelId, channelProductId, channelSpecId);

        SellPack pack;
        if (existing.isPresent()) {
            // UPDATE
            pack = existing.get();
            pack.setChannelProductName(channelProductName);
            pack.setChannelSpecName(channelSpecName);
            pack.setSellingPrice(sellingPrice);
            pack.setStatus(packStatus);
            pack.setVisibility(visibility);
            if (quantity != null) {
                pack.setQuantity(quantity);
            }
            pack.setLastSyncAt(LocalDateTime.now());
            log.info("SYNC_PACK updating sell_pack id={} channelProductId={} channelSpecId={} merchantId={}",
                    pack.getId(), channelProductId, channelSpecId, merchantId);
        } else {
            // INSERT
            pack = SellPack.builder()
                    .id(NanoIdUtil.generateComposite(merchantId))
                    .merchantId(merchantId)
                    .productId(productId)
                    .channelId(channelId)
                    .sku(sku)
                    .channelProductId(channelProductId)
                    .channelSpecId(channelSpecId)
                    .channelProductName(channelProductName)
                    .channelSpecName(channelSpecName)
                    .sellingPrice(sellingPrice)
                    .quantity(quantity != null ? quantity : 0)
                    .status(packStatus)
                    .visibility(visibility)
                    .lastSyncAt(LocalDateTime.now())
                    .build();
            log.info("SYNC_PACK inserting new sell_pack channelProductId={} channelSpecId={} merchantId={}",
                    channelProductId, channelSpecId, merchantId);
        }

        sellPackRepository.save(pack);
        log.info("SYNC_PACK completed for merchantId={} channelId={} channelProductId={} channelSpecId={}",
                merchantId, channelId, channelProductId, channelSpecId);
    }
}
