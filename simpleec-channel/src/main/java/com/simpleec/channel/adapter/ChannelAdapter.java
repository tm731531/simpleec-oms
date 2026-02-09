package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ChannelType;
import com.simpleec.core.entity.Order;
import com.simpleec.core.entity.SellPack;

import java.util.List;
import java.util.Map;

/**
 * Channel Adapter 介面 - 所有通路必須實作
 * 每個通路（momo, shopee, yahoo, pchome）各自實作此介面
 */
public interface ChannelAdapter {

    /** 此 adapter 對應的通路類型 */
    ChannelType getChannelType();

    /** 驗證通路連線 Token 是否有效 */
    boolean validateConnection(Map<String, String> credentials);

    // ==================== 商品相關 ====================

    /** 上架商品到通路 */
    String createListing(String channelId, SellPack sellPack, Map<String, Object> extraData);

    /** 更新通路上的商品資訊 */
    void updateListing(String channelId, SellPack sellPack, Map<String, Object> extraData);

    /** 更新價格 */
    void updatePrice(String channelId, String channelProductId, java.math.BigDecimal price);

    /** 更新庫存 */
    void updateQuantity(String channelId, String channelProductId, int quantity);

    /** 開賣 */
    void startSelling(String channelId, String channelProductId);

    /** 停售 */
    void stopSelling(String channelId, String channelProductId);

    // ==================== 訂單相關 ====================

    /** 從通路拉取訂單 */
    List<Order> fetchOrders(String channelId, java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** 確認出貨 */
    void confirmShipment(String channelId, String channelOrderId, String trackingNumber, String logisticsCompany);

    /** 接受買家取消 */
    void acceptCancellation(String channelId, String channelOrderId);

    /** 取得出貨編號 / 物流單號 */
    String getShippingLabel(String channelId, String channelOrderId);
}
