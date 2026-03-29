package com.simpleec.core.entity;

import com.simpleec.common.enums.ShipmentExceptionTypeEnum;
import com.simpleec.common.enums.ShipmentStatusEnum;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shipments", indexes = {
    @Index(name = "idx_shipments_merchant",      columnList = "merchant_id"),
    @Index(name = "idx_shipments_batch",         columnList = "batch_id"),
    @Index(name = "idx_shipments_status",        columnList = "merchant_id,status"),
    @Index(name = "idx_shipments_merchant_date", columnList = "merchant_id,created_at"),
    @Index(name = "idx_shipments_channel",       columnList = "channel_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Shipment {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "channel_id", length = 20, nullable = false)
    private String channelId;

    @Column(name = "batch_id", length = 20)
    private String batchId;

    @Column(name = "shipment_no", length = 50)
    private String shipmentNo;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "carrier", length = 100)
    private String carrier;

    @Column(name = "logistics_cost", precision = 10, scale = 2)
    private BigDecimal logisticsCost;

    @Column(name = "status", length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ShipmentStatusEnum status = ShipmentStatusEnum.PICKING_LIST;

    @Builder.Default
    @Column(name = "has_exception", nullable = false)
    private boolean hasException = false;

    @Column(name = "exception_type", length = 30)
    @Enumerated(EnumType.STRING)
    private ShipmentExceptionTypeEnum exceptionType;

    @Column(name = "exception_note")
    private String exceptionNote;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "platform_notified_at")
    private LocalDateTime platformNotifiedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "notes")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private int version = 0;

    @Column(name = "shipment_type", length = 30, nullable = false)
    @Builder.Default
    private String shipmentType = "HOME_DELIVERY";

    // CVS preparatory fields (nullable — no business logic in v1)
    @Column(name = "cvs_store_code", length = 20)
    private String cvsStoreCode;

    @Column(name = "cvs_store_name", length = 100)
    private String cvsStoreName;

    @Column(name = "cvs_recipient_name", length = 100)
    private String cvsRecipientName;

    @Column(name = "cvs_phone_last5", length = 5)
    private String cvsPhoneLast5;

    @Builder.Default
    @Column(name = "is_cod", nullable = false)
    private boolean isCod = false;

    @Column(name = "cod_amount", precision = 10, scale = 2)
    private BigDecimal codAmount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
