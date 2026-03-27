package com.simpleec.core.dto;

import java.math.BigDecimal;

public interface OrderStatsResult {
    // 業務視角：當日新增訂單（channel_created_at = statDate）
    Integer getNewOrderCount();
    BigDecimal getNewOrderAmount();

    // 老闆視角：營業額（排除 cancelled）
    Integer getGrossOrderCount();
    BigDecimal getGrossAmount();

    // 財務視角：實收（confirmed 以上狀態）
    Integer getReceivedCount();
    BigDecimal getReceivedAmount();

    // 物流視角
    Integer getShippedCount();
    Integer getCompletedCount();
    Integer getCancelledCount();

    // 商品統計
    Integer getItemSoldCount();
}
