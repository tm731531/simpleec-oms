package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_statistics")
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

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "order_count")
    private Integer orderCount;

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "shipped_count")
    private Integer shippedCount;

    @Column(name = "completed_count")
    private Integer completedCount;

    @Column(name = "cancelled_count")
    private Integer cancelledCount;

    @Column(name = "refund_count")
    private Integer refundCount;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
