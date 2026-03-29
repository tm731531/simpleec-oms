package com.simpleec.channeljob.handler;

import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SYNC_PACK channel-job handler — fetches product+variant catalogue from the platform
 * and publishes one SYNC_PACK event per variant to task.backend.
 *
 * Currently supports Cyberbiz. Other platforms can be added when their adapters
 * expose a fetchProducts() equivalent.
 *
 * Flow:
 *   {platform}.slow  →  ChannelJobConsumer  →  SyncPackChannelHandler
 *       → CyberbizAdapter.fetchProducts()
 *           → for each product×variant: publish SYNC_PACK → task.backend
 *               → SyncPackHandler (backend-job): upsert sell_pack (+ product if new)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncPackChannelHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Handle a SYNC_PACK task dispatched by the scheduler.
     *
     * @param platformCode platform code ("cyberbiz", etc.)
     * @param channelId    channel instance ID
     * @param merchantId   merchant ID resolved from the database
     * @param adapter      CyberbizAdapter with credentials already set
     */
    public void handleSyncPack(String platformCode, String channelId,
                               String merchantId, CyberbizAdapter adapter) {
        log.info("SYNC_PACK started: platform={}, channel={}, merchant={}", platformCode, channelId, merchantId);

        List<Map<String, Object>> products;
        try {
            products = adapter.fetchProducts(channelId);
        } catch (Exception e) {
            log.error("SYNC_PACK failed to fetch products from platform={} channel={}", platformCode, channelId, e);
            return;
        }

        if (products.isEmpty()) {
            log.info("SYNC_PACK: no products returned for channel={}", channelId);
            return;
        }

        int published = 0;
        for (Map<String, Object> product : products) {
            published += publishVariants(product, platformCode, channelId, merchantId);
        }

        log.info("SYNC_PACK completed: platform={}, channel={}, merchant={}, published={} variant events",
                platformCode, channelId, merchantId, published);
    }

    /**
     * For each variant inside a product, publish one SYNC_PACK event to task.backend.
     *
     * @return number of events published
     */
    @SuppressWarnings("unchecked")
    private int publishVariants(Map<String, Object> product, String platformCode,
                                String channelId, String merchantId) {
        Object productId = product.get("id");
        Object productTitle = product.get("title");
        Object published = product.get("published");

        List<Map<String, Object>> variants = (List<Map<String, Object>>) product.get("product_variants");
        if (variants == null || variants.isEmpty()) {
            log.debug("SYNC_PACK: product id={} has no variants, skipping", productId);
            return 0;
        }

        int count = 0;
        for (Map<String, Object> variant : variants) {
            try {
                publishSyncPackEvent(platformCode, channelId, merchantId,
                        productId, productTitle, published, variant);
                count++;
            } catch (Exception e) {
                log.error("SYNC_PACK failed to publish event for variant={} product={} channel={}",
                        variant.get("id"), productId, channelId, e);
            }
        }
        return count;
    }

    private void publishSyncPackEvent(String platformCode, String channelId, String merchantId,
                                      Object productId, Object productTitle, Object published,
                                      Map<String, Object> variant) {
        String channelProductId = productId != null ? String.valueOf(productId) : null;
        String channelSpecId = variant.get("id") != null ? String.valueOf(variant.get("id")) : null;
        String sku = (String) variant.get("sku");
        String channelSpecName = resolveSpecName(variant);

        // selling_price: Cyberbiz returns price as string or number
        Object priceObj = variant.get("price");
        String sellingPrice = priceObj != null ? String.valueOf(priceObj) : null;

        // inventory quantity
        Object qtyObj = variant.get("inventory_quantity");
        int quantity = qtyObj instanceof Number ? ((Number) qtyObj).intValue() : 0;

        // pack status derived from product.published
        String packStatus = Boolean.TRUE.equals(published) ? "active" : "draft";

        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_" + NanoIdUtil.generate());
        header.put("requestId", "req_" + NanoIdUtil.generate());
        header.put("taskType", TaskTypeEnum.SYNC_PACK.getCode());
        header.put("platformId", platformCode);
        header.put("channelId", channelId);
        header.put("merchantId", merchantId);
        header.put("timestamp", Instant.now().toString());
        header.put("source", "channel_job");
        header.put("version", 1);
        header.put("isRollback", false);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelProductId", channelProductId);
        body.put("channelSpecId", channelSpecId);
        body.put("sku", sku);
        body.put("channelProductName", productTitle != null ? String.valueOf(productTitle) : null);
        body.put("channelSpecName", channelSpecName);
        if (sellingPrice != null) {
            try {
                body.put("sellingPrice", new java.math.BigDecimal(sellingPrice));
            } catch (NumberFormatException e) {
                log.debug("SYNC_PACK: could not parse price '{}' for variant={}", sellingPrice, channelSpecId);
            }
        }

        ObjectNode packInfo = objectMapper.createObjectNode();
        packInfo.put("packStatus", packStatus);
        packInfo.put("quantity", quantity);
        body.set("packInfo", packInfo);

        message.set("header", header);
        message.set("body", body);

        kafkaTemplate.send(TopicConstants.TASK_BACKEND, channelId, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish SYNC_PACK to task.backend: channelSpecId={} channel={}",
                                channelSpecId, channelId, ex);
                    } else {
                        log.debug("Published SYNC_PACK for variant={} product={} sku={}",
                                channelSpecId, channelProductId, sku);
                    }
                });
    }

    /**
     * Build a human-readable spec name from variant option fields.
     * Falls back to variant.name if no options are set.
     */
    private String resolveSpecName(Map<String, Object> variant) {
        StringBuilder sb = new StringBuilder();
        for (String opt : new String[]{"option1", "option2", "option3"}) {
            Object val = variant.get(opt);
            if (val != null && !val.toString().isBlank() && !"null".equalsIgnoreCase(val.toString())) {
                if (sb.length() > 0) sb.append("/");
                sb.append(val);
            }
        }
        if (sb.length() > 0) return sb.toString();
        Object name = variant.get("name");
        return name != null ? String.valueOf(name) : null;
    }
}
