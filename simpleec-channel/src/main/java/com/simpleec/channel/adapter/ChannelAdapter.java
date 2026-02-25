package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ModeEnum;
import java.util.List;
import java.util.Map;

/**
 * 通路適配器介面
 *
 * 每個通路都要實現此介面，定義如何與通路 API 交互
 */
public interface ChannelAdapter {

    /**
     * 獲取通路代碼
     */
    String getPlatformCode();

    /**
     * 獲取通路處理模式（A 或 B）
     */
    ModeEnum getMode();

    /**
     * Mode A: 拉取完整訂單列表
     * 返回格式：List of Map，每個 Map 代表一筆訂單的完整數據
     *
     * @param channelId 通路 ID（用於獲取 channel 配置如 token）
     * @param timeRange 時間範圍 (e.g., "last_5_minutes")
     * @return 訂單列表
     */
    List<Map<String, Object>> fetchOrders(String channelId, String timeRange) throws Exception;

    /**
     * Mode A: 拉取完整訂單列表（根據時間戳）
     *
     * 調用兩次 API：
     * - 新建訂單：baseTimestamp - 7 天 ～ baseTimestamp
     * - 更新訂單：baseTimestamp - 1 天 ～ baseTimestamp
     * 然後合併去重
     *
     * @param channelId 通路 ID
     * @param baseTimestamp 基礎時間戳（心跳時間，秒），用於計算時間窗口
     * @return 訂單列表（完整數據，已去重）
     */
    List<Map<String, Object>> fetchOrdersByTimestamp(String channelId, long baseTimestamp) throws Exception;

    /**
     * Mode B: 拉取訂單列表（概要，不含詳情）
     *
     * @param channelId 通路 ID（用於獲取 channel 配置如 token）
     * @param timeRange 時間範圍
     * @return 訂單 ID 或概要列表
     */
    List<String> fetchOrderList(String channelId, String timeRange) throws Exception;

    /**
     * Mode B: 拉取訂單列表（根據時間戳）
     *
     * @param channelId 通路 ID
     * @param baseTimestamp 基礎時間戳（心跳時間，秒），用於計算時間窗口
     * @return 訂單 ID 列表
     */
    List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) throws Exception;

    /**
     * Mode B: 拉取單筆訂單詳情
     *
     * @param channelId 通路 ID（用於獲取 channel 配置如 token）
     * @param orderId   通路訂單 ID
     * @return 訂單完整數據
     */
    Map<String, Object> fetchOrderDetail(String channelId, String orderId) throws Exception;

    /**
     * 拉取退貨列表
     *
     * @param channelId 通路 ID（用於獲取 channel 配置如 token）
     * @param timeRange 時間範圍
     */
    List<Map<String, Object>> fetchReturns(String channelId, String timeRange) throws Exception;

    /**
     * 執行出貨
     */
    void shipOrder(String orderId, Map<String, Object> shippingInfo) throws Exception;

    /**
     * 更新庫存
     */
    void updateInventory(String productId, int quantity) throws Exception;

    /**
     * 驗證連接是否正常
     */
    boolean testConnection() throws Exception;
}
