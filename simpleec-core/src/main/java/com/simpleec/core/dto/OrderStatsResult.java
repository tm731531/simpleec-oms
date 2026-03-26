package com.simpleec.core.dto;

import java.math.BigDecimal;

public interface OrderStatsResult {
    Integer getOrderCount();
    BigDecimal getTotalAmount();
    Integer getShippedCount();
    Integer getCompletedCount();
    Integer getCancelledCount();
}
