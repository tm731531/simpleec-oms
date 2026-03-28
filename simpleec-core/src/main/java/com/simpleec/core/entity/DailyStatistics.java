package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_statistics")
@IdClass(DailyStatisticsId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStatistics {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false, length = 20)
    private String merchantId;

    @Column(name = "platform_id", nullable = false, length = 20)
    private String platformId;

    @Column(name = "channel_id", nullable = false, length = 20)
    private String channelId;

    @Id
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    // 業務視角：當日新增訂單（channel_created_at = statDate）
    @Column(name = "new_order_count")
    private Integer newOrderCount;

    @Column(name = "new_order_amount", precision = 15, scale = 2)
    private BigDecimal newOrderAmount;

    // 老闆視角：營業額（排除 cancelled）
    @Column(name = "gross_order_count")
    private Integer grossOrderCount;

    @Column(name = "gross_amount", precision = 15, scale = 2)
    private BigDecimal grossAmount;

    // 財務視角：實收（confirmed 以上狀態）
    @Column(name = "received_count")
    private Integer receivedCount;

    @Column(name = "received_amount", precision = 15, scale = 2)
    private BigDecimal receivedAmount;

    @Column(name = "refund_count")
    private Integer refundCount;

    @Column(name = "refund_amount", precision = 15, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "net_amount", precision = 15, scale = 2)
    private BigDecimal netAmount;

    // 物流視角
    @Column(name = "shipped_count")
    private Integer shippedCount;

    @Column(name = "completed_count")
    private Integer completedCount;

    @Column(name = "cancelled_count")
    private Integer cancelledCount;

    // 商品統計
    @Column(name = "item_sold_count")
    private Integer itemSoldCount;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
