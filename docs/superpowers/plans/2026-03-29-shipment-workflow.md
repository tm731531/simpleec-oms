# Shipment Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `order_shipments` with a full warehouse shipment workflow (拿貨→裝箱→貼標→出貨) supporting 拆包, 混包, and auto Kafka platform sync on dispatch.

**Architecture:** `ShipmentService` in `simpleec-core` owns all business logic (create, split, merge, dispatch). `simpleec-api` controllers are thin HTTP wrappers. Dispatch is a two-phase operation: atomic DB transaction (status + order update) followed by Kafka publish outside the transaction.

**Tech Stack:** Spring Boot 3.5, JPA/Hibernate (PostgreSQL 16), Flyway V2 migration, Redis distributed lock (split/merge), Kafka SHIP_ORDER v2 event.

---

## File Map

| Action | File |
|--------|------|
| Create | `simpleec-common/.../enums/ShipmentStatusEnum.java` |
| Create | `simpleec-common/.../enums/ShipmentExceptionTypeEnum.java` |
| Modify | `simpleec-common/.../enums/OrderStatusEnum.java` |
| Create | `simpleec-core/.../entity/Shipment.java` |
| Create | `simpleec-core/.../entity/ShipmentItem.java` |
| Create | `simpleec-core/.../entity/ShipmentBatch.java` |
| Create | `simpleec-core/.../entity/ShipmentStatusLog.java` |
| Create | `simpleec-core/.../repository/ShipmentRepository.java` |
| Create | `simpleec-core/.../repository/ShipmentItemRepository.java` |
| Create | `simpleec-core/.../repository/ShipmentBatchRepository.java` |
| Create | `simpleec-core/.../repository/ShipmentStatusLogRepository.java` |
| Create | `simpleec-core/.../service/ShipmentService.java` |
| Replace | `simpleec-api/.../controller/UserShipmentController.java` |
| Create | `simpleec-api/.../controller/UserShipmentBatchController.java` |
| Delete | `simpleec-core/.../entity/OrderShipment.java` |
| Delete | `simpleec-core/.../repository/OrderShipmentRepository.java` |
| Modify | `simpleec-api/.../controller/UserOrderController.java` |
| Create | `simpleec-api/src/main/resources/db/migration/V2__shipment_workflow.sql` |
| Modify | `docker/init-db/01-schema.sql` |
| Modify | `simpleec-channel-job/.../handler/ShipOrderHandler.java` |

---

## Task 1: Flyway V2 Migration + Schema

**Files:**
- Create: `simpleec-api/src/main/resources/db/migration/V2__shipment_workflow.sql`
- Modify: `docker/init-db/01-schema.sql`

- [ ] **Step 1: Write V2 migration SQL**

Create `simpleec-api/src/main/resources/db/migration/V2__shipment_workflow.sql`:

```sql
-- V2: Replace order_shipments with full shipment workflow tables
-- Spec: docs/superpowers/specs/2026-03-29-shipment-workflow-design.md

-- -------------------------------------------------------------------------
-- shipment_batches — vehicle/dispatch run (optional, batch_mode merchants)
-- -------------------------------------------------------------------------
CREATE TABLE public.shipment_batches (
    id                   VARCHAR(20)    NOT NULL,
    merchant_id          VARCHAR(20)    NOT NULL,
    batch_no             VARCHAR(50),
    carrier              VARCHAR(100),
    logistics_cost       DECIMAL(10,2),
    scheduled_pickup_at  TIMESTAMPTZ,
    actual_pickup_at     TIMESTAMPTZ,
    carrier_driver_id    VARCHAR(100),
    handoff_box_count    INTEGER,
    status               VARCHAR(30)    NOT NULL DEFAULT 'PREPARING',
    force_ready_note     TEXT,
    notes                TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_batch_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION
);
CREATE INDEX idx_shipment_batches_merchant ON public.shipment_batches (merchant_id);

-- -------------------------------------------------------------------------
-- shipments — one per physical box (tracking number)
-- -------------------------------------------------------------------------
CREATE TABLE public.shipments (
    id                    VARCHAR(20)    NOT NULL,
    merchant_id           VARCHAR(20)    NOT NULL,
    channel_id            VARCHAR(20)    NOT NULL,
    batch_id              VARCHAR(20),
    shipment_no           VARCHAR(50),
    tracking_number       VARCHAR(100),
    carrier               VARCHAR(100),
    logistics_cost        DECIMAL(10,2),
    status                VARCHAR(30)    NOT NULL DEFAULT 'PICKING_LIST',
    has_exception         BOOLEAN        NOT NULL DEFAULT FALSE,
    exception_type        VARCHAR(30),
    exception_note        TEXT,
    dispatched_at         TIMESTAMPTZ,
    platform_notified_at  TIMESTAMPTZ,
    cancelled_at          TIMESTAMPTZ,
    cancel_reason         TEXT,
    notes                 TEXT,
    version               INTEGER        NOT NULL DEFAULT 0,
    shipment_type         VARCHAR(30)    NOT NULL DEFAULT 'HOME_DELIVERY',
    cvs_store_code        VARCHAR(20),
    cvs_store_name        VARCHAR(100),
    cvs_recipient_name    VARCHAR(100),
    cvs_phone_last5       VARCHAR(5),
    is_cod                BOOLEAN        NOT NULL DEFAULT FALSE,
    cod_amount            DECIMAL(10,2),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_shipment_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_shipment_batch FOREIGN KEY (batch_id)
        REFERENCES public.shipment_batches (id) ON UPDATE CASCADE ON DELETE SET NULL
);
CREATE INDEX idx_shipments_merchant      ON public.shipments (merchant_id);
CREATE INDEX idx_shipments_batch         ON public.shipments (batch_id);
CREATE INDEX idx_shipments_status        ON public.shipments (merchant_id, status);
CREATE INDEX idx_shipments_merchant_date ON public.shipments (merchant_id, created_at DESC);
CREATE INDEX idx_shipments_channel       ON public.shipments (channel_id);

-- -------------------------------------------------------------------------
-- shipment_items — order line-items allocated to a box
-- -------------------------------------------------------------------------
CREATE TABLE public.shipment_items (
    id               VARCHAR(20)  NOT NULL,
    shipment_id      VARCHAR(20)  NOT NULL,
    merchant_id      VARCHAR(20)  NOT NULL,
    order_id         VARCHAR(20)  NOT NULL,
    channel_order_id VARCHAR(100),
    items            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_item_shipment FOREIGN KEY (shipment_id)
        REFERENCES public.shipments (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_shipment_item_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE NO ACTION
);
CREATE INDEX idx_shipment_items_shipment ON public.shipment_items (shipment_id);
CREATE INDEX idx_shipment_items_order    ON public.shipment_items (order_id);
CREATE INDEX idx_shipment_items_merchant ON public.shipment_items (merchant_id);
CREATE INDEX idx_shipment_items_gin      ON public.shipment_items USING GIN (items);

-- -------------------------------------------------------------------------
-- shipment_status_logs — audit trail for status changes
-- -------------------------------------------------------------------------
CREATE TABLE public.shipment_status_logs (
    id           VARCHAR(20)  NOT NULL,
    shipment_id  VARCHAR(20)  NOT NULL,
    from_status  VARCHAR(30),
    to_status    VARCHAR(30)  NOT NULL,
    operator_id  VARCHAR(100),
    remark       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_status_log FOREIGN KEY (shipment_id)
        REFERENCES public.shipments (id) ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX idx_shipment_status_log ON public.shipment_status_logs (shipment_id);

-- -------------------------------------------------------------------------
-- Migrate existing order_shipments → shipments (all as DISPATCHED)
-- -------------------------------------------------------------------------
INSERT INTO public.shipments
    (id, merchant_id, channel_id, shipment_no, tracking_number, carrier,
     status, dispatched_at, platform_notified_at, created_at, updated_at)
SELECT
    os.id,
    o.merchant_id,
    o.channel_id,
    'LEGACY-' || os.id,
    os.tracking_number,
    os.logistics_company,
    'DISPATCHED',
    os.shipped_at,
    os.shipped_at,
    os.created_at,
    os.created_at
FROM public.order_shipments os
JOIN public.orders o ON o.id = os.order_id;

-- -------------------------------------------------------------------------
-- Drop order_shipments (replaced by shipments)
-- -------------------------------------------------------------------------
DROP TABLE public.order_shipments;
```

- [ ] **Step 2: Update docker/init-db/01-schema.sql**

In `docker/init-db/01-schema.sql`, replace the `order_shipments` block (lines ~362–379) with the four new tables from Step 1 (same DDL, for fresh container installs). Also remove the old `order_shipments` CREATE TABLE and its indexes.

Also update `docker/init-db/02-seed-data.sql`: remove the `INSERT INTO public.order_shipments` block (lines ~227–231). These rows reference the now-deleted table; leaving them causes fresh `docker compose up` to fail at seed data load. The Flyway V2 migration already migrates any existing `order_shipments` data for active installations.

- [ ] **Step 3: Verify migration compiles and runs**

```bash
cd /home/tom/ONEEC/simpleec-oms
./gradlew :simpleec-api:build -x test 2>&1 | grep -E "BUILD|ERROR|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add simpleec-api/src/main/resources/db/migration/V2__shipment_workflow.sql \
        docker/init-db/01-schema.sql \
        docker/init-db/02-seed-data.sql
git commit -m "feat: add Flyway V2 migration — shipment workflow tables, replace order_shipments"
```

---

## Task 2: Enums

**Files:**
- Create: `simpleec-common/src/main/java/com/simpleec/common/enums/ShipmentStatusEnum.java`
- Create: `simpleec-common/src/main/java/com/simpleec/common/enums/ShipmentExceptionTypeEnum.java`
- Modify: `simpleec-common/src/main/java/com/simpleec/common/enums/OrderStatusEnum.java`

- [ ] **Step 1: Create ShipmentStatusEnum**

```java
package com.simpleec.common.enums;

public enum ShipmentStatusEnum {
    PICKING_LIST("PICKING_LIST", "整理清單"),
    PICKING("PICKING",           "找貨中"),
    PACKING("PACKING",           "裝箱中"),
    LABELING("LABELING",         "貼標中"),
    AWAITING_PICKUP("AWAITING_PICKUP", "等待取件"),
    DISPATCHED("DISPATCHED",     "已取件"),
    ON_HOLD("ON_HOLD",           "暫停處理"),
    CANCELLED("CANCELLED",       "已取消");

    private final String code;
    private final String label;

    ShipmentStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode()  { return code; }
    public String getLabel() { return label; }

    public static ShipmentStatusEnum fromCode(String code) {
        if (code == null) return null;
        String upper = code.toUpperCase().trim();
        for (ShipmentStatusEnum e : values()) {
            if (e.code.equals(upper)) return e;
        }
        throw new IllegalArgumentException("Unknown ShipmentStatus: " + code);
    }

    /** Returns true if this is a terminal state (no further transitions). */
    public boolean isTerminal() {
        return this == DISPATCHED || this == CANCELLED;
    }
}
```

- [ ] **Step 2: Create ShipmentExceptionTypeEnum**

```java
package com.simpleec.common.enums;

public enum ShipmentExceptionTypeEnum {
    OUT_OF_STOCK("OUT_OF_STOCK", "缺貨"),
    DAMAGED("DAMAGED",           "破損品"),
    ADDRESS_ERROR("ADDRESS_ERROR", "地址錯誤"),
    OTHER("OTHER",               "其他");

    private final String code;
    private final String label;

    ShipmentExceptionTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode()  { return code; }
    public String getLabel() { return label; }
}
```

- [ ] **Step 3: Add PARTIALLY_SHIPPED to OrderStatusEnum**

In `simpleec-common/src/main/java/com/simpleec/common/enums/OrderStatusEnum.java`, add after `READY_TO_SHIP`:

```java
PARTIALLY_SHIPPED("PARTIALLY_SHIPPED", "部分出貨", "訂單已部分出貨，尚有商品待出"),
```

The full enum values block becomes:
```java
PENDING("PENDING", "待支付", "等待買家支付款項"),
CONFIRMED("CONFIRMED", "已確認", "訂單已確認，待出貨"),
READY_TO_SHIP("READY_TO_SHIP", "待出貨", "準備出貨中"),
PARTIALLY_SHIPPED("PARTIALLY_SHIPPED", "部分出貨", "訂單已部分出貨，尚有商品待出"),
SHIPPING("SHIPPING", "出貨中", "訂單已出貨，運送中"),
SHIPPED("SHIPPED", "已出貨", "訂單已出貨"),
COMPLETED("COMPLETED", "已完成", "訂單交易完成"),
CANCELLED("CANCELLED", "已取消", "訂單已取消"),
```

- [ ] **Step 4: Build to verify**

```bash
./gradlew :simpleec-common:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add simpleec-common/src/main/java/com/simpleec/common/enums/
git commit -m "feat: add ShipmentStatusEnum, ShipmentExceptionTypeEnum, OrderStatusEnum.PARTIALLY_SHIPPED"
```

---

## Task 3: Core Entities + Repositories

**Files:**
- Create: `simpleec-core/src/main/java/com/simpleec/core/entity/Shipment.java`
- Create: `simpleec-core/src/main/java/com/simpleec/core/entity/ShipmentItem.java`
- Create: `simpleec-core/src/main/java/com/simpleec/core/entity/ShipmentBatch.java`
- Create: `simpleec-core/src/main/java/com/simpleec/core/entity/ShipmentStatusLog.java`
- Create: `simpleec-core/src/main/java/com/simpleec/core/repository/ShipmentRepository.java`
- Create: `simpleec-core/src/main/java/com/simpleec/core/repository/ShipmentItemRepository.java`
- Create: `simpleec-core/src/main/java/com/simpleec/core/repository/ShipmentBatchRepository.java`
- Create: `simpleec-core/src/main/java/com/simpleec/core/repository/ShipmentStatusLogRepository.java`
- Delete: `simpleec-core/src/main/java/com/simpleec/core/entity/OrderShipment.java`
- Delete: `simpleec-core/src/main/java/com/simpleec/core/repository/OrderShipmentRepository.java`

- [ ] **Step 1: Create Shipment entity**

```java
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
```

- [ ] **Step 2: Create ShipmentItem entity**

```java
package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "shipment_items", indexes = {
    @Index(name = "idx_shipment_items_shipment", columnList = "shipment_id"),
    @Index(name = "idx_shipment_items_order",    columnList = "order_id"),
    @Index(name = "idx_shipment_items_merchant", columnList = "merchant_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentItem {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "shipment_id", length = 20, nullable = false)
    private String shipmentId;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "order_id", length = 20, nullable = false)
    private String orderId;

    @Column(name = "channel_order_id", length = 100)
    private String channelOrderId;

    /** JSON array: [{channel_item_id, sku, name, quantity, picked, warehouse_location, barcode}] */
    @Column(name = "items", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("'[]'::jsonb")
    @Builder.Default
    private String items = "[]";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Create ShipmentBatch entity**

```java
package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shipment_batches", indexes = {
    @Index(name = "idx_shipment_batches_merchant", columnList = "merchant_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentBatch {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "merchant_id", length = 20, nullable = false)
    private String merchantId;

    @Column(name = "batch_no", length = 50)
    private String batchNo;

    @Column(name = "carrier", length = 100)
    private String carrier;

    @Column(name = "logistics_cost", precision = 10, scale = 2)
    private BigDecimal logisticsCost;

    @Column(name = "scheduled_pickup_at")
    private LocalDateTime scheduledPickupAt;

    @Column(name = "actual_pickup_at")
    private LocalDateTime actualPickupAt;

    @Column(name = "carrier_driver_id", length = 100)
    private String carrierDriverId;

    @Column(name = "handoff_box_count")
    private Integer handoffBoxCount;

    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private String status = "PREPARING";

    @Column(name = "force_ready_note")
    private String forceReadyNote;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Create ShipmentStatusLog entity**

```java
package com.simpleec.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "shipment_status_logs", indexes = {
    @Index(name = "idx_shipment_status_log", columnList = "shipment_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentStatusLog {

    @Id
    @Column(name = "id", length = 20, nullable = false)
    private String id;

    @Column(name = "shipment_id", length = 20, nullable = false)
    private String shipmentId;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", length = 30, nullable = false)
    private String toStatus;

    @Column(name = "operator_id", length = 100)
    private String operatorId;

    @Column(name = "remark")
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 5: Create four repositories**

```java
// ShipmentRepository.java
package com.simpleec.core.repository;

import com.simpleec.common.enums.ShipmentStatusEnum;
import com.simpleec.core.entity.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShipmentRepository extends JpaRepository<Shipment, String> {
    Page<Shipment> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);
    List<Shipment> findByMerchantIdAndStatus(String merchantId, ShipmentStatusEnum status);
    List<Shipment> findByBatchId(String batchId);

    /** Count non-CANCELLED shipments in a batch that have not yet reached LABELING or beyond. */
    @Query("SELECT COUNT(s) FROM Shipment s WHERE s.batchId = :batchId " +
           "AND s.status NOT IN ('LABELING','AWAITING_PICKUP','DISPATCHED','CANCELLED')")
    long countBatchShipmentsNotLabeled(@org.springframework.data.repository.query.Param("batchId") String batchId);

    /** Count non-CANCELLED shipments in a batch total. */
    @Query("SELECT COUNT(s) FROM Shipment s WHERE s.batchId = :batchId AND s.status <> 'CANCELLED'")
    long countActiveBatchShipments(@org.springframework.data.repository.query.Param("batchId") String batchId);
}
```

```java
// ShipmentItemRepository.java
package com.simpleec.core.repository;

import com.simpleec.core.entity.ShipmentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShipmentItemRepository extends JpaRepository<ShipmentItem, String> {
    List<ShipmentItem> findByShipmentId(String shipmentId);
    List<ShipmentItem> findByOrderId(String orderId);
    List<ShipmentItem> findByShipmentIdIn(List<String> shipmentIds);

    /**
     * Find all active (non-cancelled) shipment items for an order.
     * Uses native SQL because ShipmentItem.shipmentId is a plain String FK (no @ManyToOne),
     * so JPQL cross-entity JOIN would fail.
     */
    @Query(value = "SELECT si.* FROM shipment_items si " +
                   "JOIN shipments s ON s.id = si.shipment_id " +
                   "WHERE si.order_id = :orderId AND s.status <> 'CANCELLED'",
           nativeQuery = true)
    List<ShipmentItem> findActiveByOrderId(@org.springframework.data.repository.query.Param("orderId") String orderId);
}
```

```java
// ShipmentBatchRepository.java
package com.simpleec.core.repository;

import com.simpleec.core.entity.ShipmentBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentBatchRepository extends JpaRepository<ShipmentBatch, String> {
    Page<ShipmentBatch> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);
}
```

```java
// ShipmentStatusLogRepository.java
package com.simpleec.core.repository;

import com.simpleec.core.entity.ShipmentStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShipmentStatusLogRepository extends JpaRepository<ShipmentStatusLog, String> {
    List<ShipmentStatusLog> findByShipmentIdOrderByCreatedAtDesc(String shipmentId);
}
```

- [ ] **Step 6: Delete old entity + repository**

```bash
rm simpleec-core/src/main/java/com/simpleec/core/entity/OrderShipment.java
rm simpleec-core/src/main/java/com/simpleec/core/repository/OrderShipmentRepository.java
```

- [ ] **Step 6b: Update UserOrderController — remove OrderShipment references (prevents build failure)**

`UserOrderController` imports `OrderShipment` and `OrderShipmentRepository` which no longer exist.
Replace the `listShipmentsForOrder` method and remove the old repository injection.

In `simpleec-api/src/main/java/com/simpleec/api/controller/UserOrderController.java`:

Remove these lines from the import block:
```java
import com.simpleec.core.entity.OrderShipment;
import com.simpleec.core.repository.OrderShipmentRepository;
```

Add this import:
```java
import com.simpleec.core.entity.ShipmentItem;
import com.simpleec.core.repository.ShipmentItemRepository;
```

Remove from the field declarations:
```java
private final OrderShipmentRepository shipmentRepository;
```

Add:
```java
private final ShipmentItemRepository shipmentItemRepository;
```

Replace the `listShipmentsForOrder` method:
```java
/**
 * GET /api/user/orders/{orderId}/shipments
 * Returns all shipment items for a specific order (uses new shipments tables).
 */
@GetMapping("/{orderId}/shipments")
public ResponseEntity<List<ShipmentItem>> listShipmentsForOrder(
        @AuthenticationPrincipal UserPrincipal principal,
        @PathVariable String orderId) {

    Optional<Order> orderOpt = orderRepository.findById(orderId);
    if (orderOpt.isEmpty() || !principal.getMerchantId().equals(orderOpt.get().getMerchantId())) {
        return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(shipmentItemRepository.findByOrderId(orderId));
}
```

**Note:** `GET /api/user/orders?status=READY_TO_SHIP` already exists and provides the "ready to ship" order list the operator needs to start the workflow. No new endpoint required.

- [ ] **Step 7: Build to verify entities compile and Hibernate validates schema**

```bash
./gradlew :simpleec-core:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/entity/ \
        simpleec-core/src/main/java/com/simpleec/core/repository/ \
        simpleec-api/src/main/java/com/simpleec/api/controller/UserOrderController.java
git commit -m "feat: add Shipment, ShipmentItem, ShipmentBatch, ShipmentStatusLog entities and repos; remove OrderShipment; update UserOrderController"
```

---

## Task 4: ShipmentService — Create + Status + Cancel + Exception

**Files:**
- Create: `simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java`

This task implements Phase 1 (create), status advances, ON_HOLD, and CANCELLED.

- [ ] **Step 1: Create ShipmentService skeleton + createShipments**

```java
package com.simpleec.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.enums.OrderStatusEnum;
import com.simpleec.common.enums.ShipmentExceptionTypeEnum;
import com.simpleec.common.enums.ShipmentStatusEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.*;
import com.simpleec.core.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final ShipmentBatchRepository shipmentBatchRepository;
    private final ShipmentStatusLogRepository shipmentStatusLogRepository;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // Status advance order — ends at AWAITING_PICKUP.
    // DISPATCHED is intentionally excluded: the only path to DISPATCHED is
    // dispatch(), which performs a two-phase commit (DB + Kafka SHIP_ORDER).
    // advanceStatus() at AWAITING_PICKUP will throw "No next status" to force
    // the operator to call the explicit dispatch endpoint.
    private static final List<ShipmentStatusEnum> STATUS_SEQUENCE = List.of(
        ShipmentStatusEnum.PICKING_LIST,
        ShipmentStatusEnum.PICKING,
        ShipmentStatusEnum.PACKING,
        ShipmentStatusEnum.LABELING,
        ShipmentStatusEnum.AWAITING_PICKUP
    );

    /**
     * Phase 1: Create one shipment per order from the selected order IDs.
     * channelId is derived from the order — not passed as a parameter.
     * Populates shipment_items from orders.items JSONB.
     * Returns the created Shipment list.
     */
    @Transactional
    public List<Shipment> createShipments(List<String> orderIds, String merchantId) {
        List<Shipment> result = new ArrayList<>();
        String datePrefix = "SHP-" + LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (String orderId : orderIds) {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            if (!order.getMerchantId().equals(merchantId)) {
                throw new IllegalArgumentException("Order " + orderId + " does not belong to merchant");
            }

            String shipmentId = NanoIdUtil.generate();
            // Use NanoID suffix to avoid collision when concurrent createShipments calls happen on the same day
            String shipmentNo = datePrefix + "-" + shipmentId.substring(0, 6).toUpperCase();

            Shipment shipment = Shipment.builder()
                .id(shipmentId)
                .merchantId(merchantId)
                .channelId(order.getChannelId())   // derive from order, not request
                .shipmentNo(shipmentNo)
                .status(ShipmentStatusEnum.PICKING_LIST)
                .build();

            shipmentRepository.save(shipment);
            logStatusChange(shipmentId, null, ShipmentStatusEnum.PICKING_LIST, "system", "Created");

            // Populate shipment_items from order.items JSONB
            ShipmentItem item = ShipmentItem.builder()
                .id(NanoIdUtil.generate())
                .shipmentId(shipmentId)
                .merchantId(merchantId)
                .orderId(orderId)
                .channelOrderId(order.getChannelOrderId())
                .items(buildInitialItemsJson(order.getItems()))
                .build();

            shipmentItemRepository.save(item);
            result.add(shipment);
        }
        return result;
    }

    /**
     * Advance shipment to next status in sequence.
     * Cannot advance DISPATCHED, CANCELLED, or ON_HOLD (resolve exception first).
     */
    @Transactional
    public Shipment advanceStatus(String shipmentId, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);

        if (shipment.isHasException()) {
            throw new IllegalStateException("Resolve exception before advancing status: " + shipmentId);
        }
        if (shipment.getStatus().isTerminal()) {
            throw new IllegalStateException("Cannot advance terminal status: " + shipment.getStatus());
        }
        if (shipment.getStatus() == ShipmentStatusEnum.ON_HOLD) {
            throw new IllegalStateException("Cannot advance ON_HOLD shipment. Resolve exception first.");
        }

        int currentIdx = STATUS_SEQUENCE.indexOf(shipment.getStatus());
        if (currentIdx < 0 || currentIdx >= STATUS_SEQUENCE.size() - 1) {
            throw new IllegalStateException("No next status for: " + shipment.getStatus());
        }

        ShipmentStatusEnum next = STATUS_SEQUENCE.get(currentIdx + 1);
        ShipmentStatusEnum prev = shipment.getStatus();

        // LABELING reached → check if batch should auto-transition to READY
        shipment.setStatus(next);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, prev, next, operatorId, null);

        if (next == ShipmentStatusEnum.LABELING && shipment.getBatchId() != null) {
            checkAndAutoReadyBatch(shipment.getBatchId());
        }

        return shipment;
    }

    /**
     * Cancel a shipment. Releases items back to READY_TO_SHIP for their orders
     * if no other active shipments remain for that order.
     */
    @Transactional
    public Shipment cancelShipment(String shipmentId, String reason, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        if (shipment.getStatus() == ShipmentStatusEnum.DISPATCHED) {
            throw new IllegalStateException("Cannot cancel a dispatched shipment");
        }

        ShipmentStatusEnum prev = shipment.getStatus();
        shipment.setStatus(ShipmentStatusEnum.CANCELLED);
        shipment.setCancelledAt(LocalDateTime.now());
        shipment.setCancelReason(reason);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, prev, ShipmentStatusEnum.CANCELLED, operatorId, reason);

        // Check if all items for affected orders are now un-allocated
        List<ShipmentItem> items = shipmentItemRepository.findByShipmentId(shipmentId);
        for (ShipmentItem si : items) {
            List<ShipmentItem> remaining = shipmentItemRepository.findActiveByOrderId(si.getOrderId());
            if (remaining.isEmpty()) {
                // No active shipments — revert order to READY_TO_SHIP
                orderRepository.findById(si.getOrderId()).ifPresent(order -> {
                    order.setOrderStatus(OrderStatusEnum.READY_TO_SHIP);
                    orderRepository.save(order);
                });
            }
        }
        return shipment;
    }

    /**
     * Raise an exception on a shipment (缺貨, 破損品, etc.). Sets ON_HOLD.
     */
    @Transactional
    public Shipment raiseException(String shipmentId, ShipmentExceptionTypeEnum type,
                                   String note, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        ShipmentStatusEnum prev = shipment.getStatus();

        shipment.setHasException(true);
        shipment.setExceptionType(type);
        shipment.setExceptionNote(note);
        shipment.setStatus(ShipmentStatusEnum.ON_HOLD);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, prev, ShipmentStatusEnum.ON_HOLD, operatorId,
            type.getLabel() + ": " + note);
        return shipment;
    }

    /**
     * Resolve an exception and resume the shipment (returns to previous status).
     */
    @Transactional
    public Shipment resolveException(String shipmentId, ShipmentStatusEnum resumeStatus,
                                     String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        if (!shipment.isHasException()) {
            throw new IllegalStateException("No exception to resolve on: " + shipmentId);
        }

        shipment.setHasException(false);
        shipment.setExceptionType(null);
        shipment.setExceptionNote(null);
        shipment.setStatus(resumeStatus);
        shipmentRepository.save(shipment);
        logStatusChange(shipmentId, ShipmentStatusEnum.ON_HOLD, resumeStatus, operatorId, "Exception resolved");
        return shipment;
    }

    // -----------------------------------------------------------------------
    // Read operations
    // -----------------------------------------------------------------------

    public Optional<Shipment> findById(String id) {
        return shipmentRepository.findById(id);
    }

    public Page<Shipment> findByMerchant(String merchantId, Pageable pageable) {
        return shipmentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }

    public List<ShipmentItem> findItemsByShipmentId(String shipmentId) {
        return shipmentItemRepository.findByShipmentId(shipmentId);
    }

    public List<ShipmentStatusLog> findStatusLogs(String shipmentId) {
        return shipmentStatusLogRepository.findByShipmentIdOrderByCreatedAtDesc(shipmentId);
    }

    /**
     * Update tracking number and carrier on a LABELING-or-later shipment.
     */
    @Transactional
    public Shipment setTracking(String shipmentId, String trackingNumber,
                                String carrier, String operatorId) {
        Shipment shipment = getAndVerify(shipmentId);
        String oldTracking = shipment.getTrackingNumber();
        shipment.setTrackingNumber(trackingNumber);
        shipment.setCarrier(carrier);
        shipmentRepository.save(shipment);
        if (oldTracking != null && !oldTracking.equals(trackingNumber)) {
            logStatusChange(shipmentId, shipment.getStatus(), shipment.getStatus(),
                operatorId, "Tracking updated: " + oldTracking + " → " + trackingNumber);
        }
        return shipment;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Shipment getAndVerify(String shipmentId) {
        return shipmentRepository.findById(shipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Shipment not found: " + shipmentId));
    }

    private void logStatusChange(String shipmentId, ShipmentStatusEnum from,
                                  ShipmentStatusEnum to, String operatorId, String remark) {
        ShipmentStatusLog log = ShipmentStatusLog.builder()
            .id(NanoIdUtil.generate())
            .shipmentId(shipmentId)
            .fromStatus(from != null ? from.name() : null)
            .toStatus(to.name())
            .operatorId(operatorId)
            .remark(remark)
            .build();
        shipmentStatusLogRepository.save(log);
    }

    private void checkAndAutoReadyBatch(String batchId) {
        long notLabeled = shipmentRepository.countBatchShipmentsNotLabeled(batchId);
        if (notLabeled == 0) {
            shipmentBatchRepository.findById(batchId).ifPresent(batch -> {
                if ("PREPARING".equals(batch.getStatus())) {
                    batch.setStatus("READY");
                    shipmentBatchRepository.save(batch);
                    log.info("Batch {} auto-transitioned to READY", batchId);
                }
            });
        }
    }

    /**
     * Build initial items JSON from orders.items JSONB.
     * orders.items format: [{channel_item_id, sku, name, quantity, ...}]
     * We copy relevant fields and add picked=false, warehouse_location=null, barcode=null.
     */
    private String buildInitialItemsJson(String orderItemsJson) {
        try {
            JsonNode orderItems = objectMapper.readTree(orderItemsJson);
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode item : orderItems) {
                ObjectNode si = objectMapper.createObjectNode();
                si.put("channel_item_id", item.path("channelItemId").asText(
                    item.path("channel_item_id").asText("")));
                si.put("sku",      item.path("sku").asText(""));
                // orders.items stores the display name as "productName" (not "name")
                si.put("name",     item.path("productName").asText(item.path("name").asText("")));
                si.put("quantity", item.path("quantity").asInt(1));
                si.put("picked",   false);
                si.putNull("warehouse_location");
                si.putNull("barcode");
                result.add(si);
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Failed to parse order items JSON, defaulting to []", e);
            return "[]";
        }
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew :simpleec-core:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java
git commit -m "feat: ShipmentService — create, advanceStatus, cancel, exception handling"
```

---

## Task 5: ShipmentService — Split + Merge (Redis Lock)

**Files:**
- Modify: `simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java`

- [ ] **Step 1: Add split() and merge() to ShipmentService**

Add these methods inside `ShipmentService` after `resolveException`:

```java
/**
 * Split: move specified channel_item_ids from shipmentId into a new shipment.
 * Uses Redis distributed lock to prevent concurrent modification.
 * Returns [originalShipment, newShipment].
 */
@Transactional
public List<Shipment> split(String shipmentId, List<String> channelItemIdsToMove,
                             String operatorId) {
    String lockKey = "shipment:" + shipmentId + ":lock";
    // Use unique value so we only delete our own lock (avoids deleting another thread's lock after TTL expiry)
    String lockValue = java.util.UUID.randomUUID().toString();
    Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 30, TimeUnit.SECONDS);
    if (!Boolean.TRUE.equals(locked)) {
        throw new IllegalStateException("Shipment is being modified by another operator: " + shipmentId);
    }
    try {
        Shipment original = getAndVerify(shipmentId);
        if (original.getStatus().isTerminal() || original.getStatus() == ShipmentStatusEnum.ON_HOLD) {
            throw new IllegalStateException("Cannot split shipment in status: " + original.getStatus());
        }

        List<ShipmentItem> allItems = shipmentItemRepository.findByShipmentId(shipmentId);

        // Create new shipment
        String newShipmentId = NanoIdUtil.generate();
        Shipment newShipment = Shipment.builder()
            .id(newShipmentId)
            .merchantId(original.getMerchantId())
            .channelId(original.getChannelId())
            .batchId(original.getBatchId())
            .shipmentNo(original.getShipmentNo() + "-B")
            .status(original.getStatus())
            .build();
        shipmentRepository.save(newShipment);
        logStatusChange(newShipmentId, null, newShipment.getStatus(), operatorId, "Split from " + shipmentId);

        Set<String> toMove = new HashSet<>(channelItemIdsToMove);

        for (ShipmentItem si : allItems) {
            try {
                JsonNode items = objectMapper.readTree(si.getItems());
                ArrayNode remaining = objectMapper.createArrayNode();
                ArrayNode moved = objectMapper.createArrayNode();

                for (JsonNode item : items) {
                    String cid = item.path("channel_item_id").asText();
                    if (toMove.contains(cid)) {
                        moved.add(item);
                    } else {
                        remaining.add(item);
                    }
                }

                if (!moved.isEmpty()) {
                    // Create new item row on new shipment
                    ShipmentItem newItem = ShipmentItem.builder()
                        .id(NanoIdUtil.generate())
                        .shipmentId(newShipmentId)
                        .merchantId(si.getMerchantId())
                        .orderId(si.getOrderId())
                        .channelOrderId(si.getChannelOrderId())
                        .items(objectMapper.writeValueAsString(moved))
                        .build();
                    shipmentItemRepository.save(newItem);
                }
                if (!remaining.isEmpty()) {
                    si.setItems(objectMapper.writeValueAsString(remaining));
                    shipmentItemRepository.save(si);
                } else {
                    // All items moved — remove the original row
                    shipmentItemRepository.delete(si);
                }
            } catch (Exception e) {
                throw new RuntimeException("Error processing shipment items during split", e);
            }
        }
        return List.of(original, newShipment);
    } finally {
        // Only delete the lock if we still own it (TTL may have expired and another thread may have re-acquired)
        if (lockValue.equals(redisTemplate.opsForValue().get(lockKey))) {
            redisTemplate.delete(lockKey);
        }
    }
}

/**
 * Merge shipmentIdB into shipmentIdA.
 * Both must belong to the same merchant and channel.
 * shipmentIdB is CANCELLED after merge.
 */
@Transactional
public Shipment merge(String shipmentIdA, String shipmentIdB, String operatorId) {
    String lockKeyA = "shipment:" + shipmentIdA + ":lock";
    String lockKeyB = "shipment:" + shipmentIdB + ":lock";
    // Use unique value so we only release locks we actually acquired
    String lockValue = java.util.UUID.randomUUID().toString();
    Boolean lockedA = redisTemplate.opsForValue().setIfAbsent(lockKeyA, lockValue, 30, TimeUnit.SECONDS);
    Boolean lockedB = redisTemplate.opsForValue().setIfAbsent(lockKeyB, lockValue, 30, TimeUnit.SECONDS);
    if (!Boolean.TRUE.equals(lockedA) || !Boolean.TRUE.equals(lockedB)) {
        // Only release locks we actually acquired — don't delete another thread's lock
        if (Boolean.TRUE.equals(lockedA)) redisTemplate.delete(lockKeyA);
        if (Boolean.TRUE.equals(lockedB)) redisTemplate.delete(lockKeyB);
        throw new IllegalStateException("One or both shipments are being modified concurrently");
    }
    try {
        Shipment a = getAndVerify(shipmentIdA);
        Shipment b = getAndVerify(shipmentIdB);

        if (!a.getMerchantId().equals(b.getMerchantId())) {
            throw new IllegalArgumentException("Cannot merge shipments from different merchants");
        }
        if (!a.getChannelId().equals(b.getChannelId())) {
            throw new IllegalArgumentException("Cannot merge shipments from different channels");
        }
        if (a.getStatus().isTerminal() || b.getStatus().isTerminal()) {
            throw new IllegalStateException("Cannot merge terminal shipments");
        }

        // Move all shipment_items from B → A
        List<ShipmentItem> bItems = shipmentItemRepository.findByShipmentId(shipmentIdB);
        for (ShipmentItem si : bItems) {
            si.setShipmentId(shipmentIdA);
            shipmentItemRepository.save(si);
        }

        // Cancel B — capture previous status BEFORE mutation (fixes audit log from_status)
        ShipmentStatusEnum prevB = b.getStatus();
        b.setStatus(ShipmentStatusEnum.CANCELLED);
        b.setCancelledAt(LocalDateTime.now());
        b.setCancelReason("Merged into " + shipmentIdA);
        shipmentRepository.save(b);
        logStatusChange(shipmentIdB, prevB, ShipmentStatusEnum.CANCELLED, operatorId,
            "Merged into " + shipmentIdA);

        return a;
    } finally {
        // Only release locks we still own (TTL-safe)
        if (lockValue.equals(redisTemplate.opsForValue().get(lockKeyA))) redisTemplate.delete(lockKeyA);
        if (lockValue.equals(redisTemplate.opsForValue().get(lockKeyB))) redisTemplate.delete(lockKeyB);
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew :simpleec-core:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java
git commit -m "feat: ShipmentService — split and merge with Redis distributed lock"
```

---

## Task 6: ShipmentService — Pick List Generation

**Files:**
- Modify: `simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java`

- [ ] **Step 1: Add pick list DTO + generation methods**

Add this inner record and these methods to `ShipmentService`:

```java
// -----------------------------------------------------------------------
// Pick list generation
// -----------------------------------------------------------------------

public record PickLineItem(
    String warehouseLocation,
    String sku,
    String name,
    int totalQuantity,
    List<String> orderIds   // which orders need this item
) {}

public record SortLineItem(
    String orderId,
    String channelOrderId,
    List<PickItem> items
) {}

public record PickItem(
    String channelItemId,
    String sku,
    String name,
    int quantity,
    boolean picked
) {}

/**
 * 拿貨清單 — consolidated by warehouse_location × sku, sorted by location then sku.
 * Picker walks the warehouse once and picks all needed quantity for each SKU.
 */
public List<PickLineItem> generatePickList(List<String> shipmentIds) {
    List<ShipmentItem> allItems = shipmentItemRepository.findByShipmentIdIn(shipmentIds);

    // location × sku → (totalQty, orderIds)
    Map<String, int[]> qtyMap   = new LinkedHashMap<>();
    Map<String, List<String>> orderMap = new LinkedHashMap<>();
    Map<String, String> skuName = new HashMap<>();

    for (ShipmentItem si : allItems) {
        try {
            JsonNode items = objectMapper.readTree(si.getItems());
            for (JsonNode item : items) {
                String location = item.path("warehouse_location").isNull()
                    ? "UNKNOWN" : item.path("warehouse_location").asText("UNKNOWN");
                String sku = item.path("sku").asText("");
                String key = location + "\t" + sku;
                int qty = item.path("quantity").asInt(1);

                qtyMap.computeIfAbsent(key, k -> new int[]{0})[0] += qty;
                orderMap.computeIfAbsent(key, k -> new ArrayList<>()).add(si.getOrderId());
                skuName.putIfAbsent(key, item.path("name").asText(""));
            }
        } catch (Exception e) {
            log.warn("Failed to parse items for shipment_item {}", si.getId(), e);
        }
    }

    return qtyMap.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> {
            String[] parts = e.getKey().split("\t", 2);
            return new PickLineItem(parts[0], parts[1],
                skuName.getOrDefault(e.getKey(), ""),
                e.getValue()[0],
                orderMap.getOrDefault(e.getKey(), List.of()));
        })
        .collect(Collectors.toList());
}

/**
 * 分貨清單 — per-order breakdown for the packing station.
 * Shows what to put in each order's box after picking.
 */
public List<SortLineItem> generateSortList(List<String> shipmentIds) {
    List<ShipmentItem> allItems = shipmentItemRepository.findByShipmentIdIn(shipmentIds);

    return allItems.stream().map(si -> {
        List<PickItem> items = new ArrayList<>();
        try {
            JsonNode itemsNode = objectMapper.readTree(si.getItems());
            for (JsonNode item : itemsNode) {
                items.add(new PickItem(
                    item.path("channel_item_id").asText(""),
                    item.path("sku").asText(""),
                    item.path("name").asText(""),
                    item.path("quantity").asInt(1),
                    item.path("picked").asBoolean(false)
                ));
            }
        } catch (Exception e) {
            log.warn("Failed to parse items for sort list: {}", si.getId(), e);
        }
        return new SortLineItem(si.getOrderId(), si.getChannelOrderId(), items);
    }).collect(Collectors.toList());
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew :simpleec-core:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java
git commit -m "feat: ShipmentService — generatePickList (拿貨清單) and generateSortList (分貨清單)"
```

---

## Task 7: ShipmentService — Dispatch (Atomic DB + Kafka)

**Files:**
- Modify: `simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java`

The dispatch method has two phases:
1. **DB transaction** (atomic): mark DISPATCHED, update order status
2. **Kafka publish** (outside transaction): SHIP_ORDER v2 event per affected order

- [ ] **Step 1: Add KafkaTemplate + TransactionTemplate + ChannelRepository to ShipmentService**

Add these to the `ShipmentService` field declarations (via `@RequiredArgsConstructor`):
```java
private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;
private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
private final com.simpleec.core.repository.ChannelRepository channelRepository;
```

`ChannelRepository` is already in `simpleec-core` (used by `UserOrderController`). `TransactionTemplate` is auto-created by Spring from `PlatformTransactionManager` (JPA auto-configures this).

**Note:** No need for `PlatformRepository`. `channel.getPlatformId()` directly returns the platform code (`"cyberbiz"`, `"shopee"`, etc.) — same pattern as `SchedulerEventHandler.dispatchFetchOrders()` line 127.

Then add these methods:

```java
/**
 * Dispatch a shipment (Phase 6).
 *
 * Phase 1 — atomic DB (via TransactionTemplate):
 *   1. status → DISPATCHED, record dispatched_at
 *   2. Update affected orders (SHIPPED or PARTIALLY_SHIPPED)
 *
 * Phase 2 — Kafka (outside transaction, after DB commits):
 *   3. Publish SHIP_ORDER v2 per affected order
 *   4. Write platform_notified_at on success; leave null for retry on failure
 *
 * NOTE: We use TransactionTemplate (not @Transactional on dispatchDb) because
 * Spring AOP @Transactional does NOT work on internal self-invocation — the proxy
 * is bypassed and no transaction would start.
 */
public Shipment dispatch(String shipmentId, String operatorId) {
    // Phase 1: atomic DB — TransactionTemplate ensures the proxy boundary is respected
    DispatchResult result = transactionTemplate.execute(status -> dispatchDb(shipmentId, operatorId));

    // Phase 2: Kafka (outside transaction — DB has already committed)
    for (OrderDispatchInfo info : result.orderInfos()) {
        publishShipOrderEvent(result.shipment(), info);
    }

    return result.shipment();
}

// No @Transactional here — transaction is managed by TransactionTemplate in dispatch()
private DispatchResult dispatchDb(String shipmentId, String operatorId) {
    Shipment shipment = getAndVerify(shipmentId);

    if (shipment.getStatus() != ShipmentStatusEnum.AWAITING_PICKUP) {
        throw new IllegalStateException(
            "Can only dispatch from AWAITING_PICKUP, current: " + shipment.getStatus());
    }
    if (shipment.isHasException()) {
        throw new IllegalStateException("Resolve exception before dispatching: " + shipmentId);
    }

    shipment.setStatus(ShipmentStatusEnum.DISPATCHED);
    shipment.setDispatchedAt(LocalDateTime.now());
    shipmentRepository.save(shipment);
    logStatusChange(shipmentId, ShipmentStatusEnum.AWAITING_PICKUP,
        ShipmentStatusEnum.DISPATCHED, operatorId, null);

    // Determine affected orders and their shipment completeness.
    // Deduplicate by orderId: a split-then-merge can leave two ShipmentItem rows
    // for the same order on the same shipment. Without deduplication, SHIP_ORDER
    // would be published twice for that order.
    List<ShipmentItem> items = shipmentItemRepository.findByShipmentId(shipmentId);
    List<OrderDispatchInfo> orderInfos = new ArrayList<>();
    Set<String> processedOrderIds = new HashSet<>();

    for (ShipmentItem si : items) {
        if (!processedOrderIds.add(si.getOrderId())) continue; // skip duplicate
        Order order = orderRepository.findById(si.getOrderId()).orElse(null);
        if (order == null) continue;

        List<ShipmentItem> activeForOrder = shipmentItemRepository.findActiveByOrderId(si.getOrderId());
        boolean fullyShipped = activeForOrder.stream()
            .allMatch(a -> {
                Shipment s = shipmentRepository.findById(a.getShipmentId()).orElse(null);
                return s != null && s.getStatus() == ShipmentStatusEnum.DISPATCHED;
            });

        OrderStatusEnum newStatus = fullyShipped
            ? OrderStatusEnum.SHIPPED : OrderStatusEnum.PARTIALLY_SHIPPED;
        order.setOrderStatus(newStatus);
        if (fullyShipped) order.setShippedAt(LocalDateTime.now());
        orderRepository.save(order);

        orderInfos.add(new OrderDispatchInfo(
            order.getId(), order.getChannelOrderId(), order.getMerchantId(),
            order.getChannelId(), si.getItems()
        ));
    }

    return new DispatchResult(shipment, orderInfos);
}

private void publishShipOrderEvent(Shipment shipment, OrderDispatchInfo info) {
    try {
        // Resolve platform code via channel lookup.
        // channel.getPlatformId() IS the platform code ("cyberbiz", "shopee", etc.) — same pattern
        // as SchedulerEventHandler.dispatchFetchOrders(). No Platform table lookup needed.
        com.simpleec.core.entity.Channel channel = channelRepository.findById(info.channelId())
            .orElseThrow(() -> new IllegalStateException("Channel not found: " + info.channelId()));
        String platformCode = channel.getPlatformId().toLowerCase();
        String topic = com.simpleec.common.constants.TopicConstants.platformFastTopic(platformCode);

        com.fasterxml.jackson.databind.node.ObjectNode message = objectMapper.createObjectNode();

        com.fasterxml.jackson.databind.node.ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId",  "msg_" + NanoIdUtil.generate());
        header.put("requestId",  "req_" + NanoIdUtil.generate());
        header.put("taskType",   "SHIP_ORDER");
        header.put("platformId", platformCode);
        header.put("channelId",  info.channelId());
        header.put("merchantId", info.merchantId());
        header.put("timestamp",  java.time.Instant.now().toString());
        header.put("source",     "shipment_service");
        header.put("version",    2);
        header.put("isRollback", false);

        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("orderId",         info.orderId());
        body.put("channelOrderId",  info.channelOrderId());
        body.put("trackingNumber",  shipment.getTrackingNumber());
        body.put("carrier",         shipment.getCarrier());
        body.set("lineItems",       objectMapper.readTree(info.itemsJson()));

        message.set("header", header);
        message.set("body", body);

        kafkaTemplate.send(topic, info.channelId(), message)
            .whenComplete((r, ex) -> {
                if (ex == null) {
                    // Update platform_notified_at — must use TransactionTemplate since this runs
                    // on the Kafka producer thread with no Spring transaction context
                    transactionTemplate.executeWithoutResult(s ->
                        shipmentRepository.findById(shipment.getId()).ifPresent(found -> {
                            found.setPlatformNotifiedAt(LocalDateTime.now());
                            shipmentRepository.save(found);
                        })
                    );
                } else {
                    log.error("SHIP_ORDER publish failed for order={} shipment={}",
                        info.orderId(), shipment.getId(), ex);
                    // platform_notified_at stays null — reconciliation job will retry
                }
            });
    } catch (Exception e) {
        log.error("Failed to build/send SHIP_ORDER event for order={}", info.orderId(), e);
    }
}

// DTOs for internal dispatch coordination
private record DispatchResult(Shipment shipment, List<OrderDispatchInfo> orderInfos) {}
private record OrderDispatchInfo(String orderId, String channelOrderId,
                                  String merchantId, String channelId, String itemsJson) {}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew :simpleec-core:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java
git commit -m "feat: ShipmentService — dispatch with atomic DB + Kafka SHIP_ORDER v2 publish"
```

---

## Task 8: REST API — Replace UserShipmentController

**Files:**
- Replace: `simpleec-api/src/main/java/com/simpleec/api/controller/UserShipmentController.java`

- [ ] **Step 1: Replace UserShipmentController**

Overwrite the entire file:

```java
package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.common.enums.ShipmentExceptionTypeEnum;
import com.simpleec.common.enums.ShipmentStatusEnum;
import com.simpleec.core.entity.Shipment;
import com.simpleec.core.entity.ShipmentItem;
import com.simpleec.core.entity.ShipmentStatusLog;
import com.simpleec.core.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/user/shipments")
@RequiredArgsConstructor
public class UserShipmentController {

    private final ShipmentService shipmentService;

    // ---- Request records ----
    // channelId removed — derived automatically from order.getChannelId() in ShipmentService
    record CreateShipmentsRequest(List<String> orderIds) {}
    record SetTrackingRequest(String trackingNumber, String carrier) {}
    record SplitRequest(List<String> channelItemIds) {}
    record MergeRequest(String shipmentIdB) {}
    record CancelRequest(String reason) {}
    record ExceptionRequest(String exceptionType, String note) {}
    record ResolveExceptionRequest(String resumeStatus) {}
    record PickListRequest(List<String> shipmentIds) {}

    @PostMapping
    public ResponseEntity<List<Shipment>> createShipments(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody CreateShipmentsRequest req) {
        List<Shipment> shipments = shipmentService.createShipments(
            req.orderIds(), p.getMerchantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(shipments);
    }

    @GetMapping
    public ResponseEntity<UserPageResponse<Shipment>> listShipments(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<Shipment> result = shipmentService.findByMerchant(
            p.getMerchantId(), PageRequest.of(page - 1, pageSize));
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getShipment(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        Optional<Shipment> opt = shipmentService.findById(id);
        if (opt.isEmpty() || !opt.get().getMerchantId().equals(p.getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        Shipment s = opt.get();
        List<ShipmentItem> items = shipmentService.findItemsByShipmentId(id);
        List<ShipmentStatusLog> logs = shipmentService.findStatusLogs(id);
        return ResponseEntity.ok(Map.of("shipment", s, "items", items, "statusLogs", logs));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Shipment> advanceStatus(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.advanceStatus(id, p.getAccountId()));
    }

    @PutMapping("/{id}/tracking")
    public ResponseEntity<Shipment> setTracking(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody SetTrackingRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(
            shipmentService.setTracking(id, req.trackingNumber(), req.carrier(), p.getAccountId()));
    }

    @PostMapping("/{id}/split")
    public ResponseEntity<List<Shipment>> split(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody SplitRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.split(id, req.channelItemIds(), p.getAccountId()));
    }

    @PostMapping("/{id}/merge")
    public ResponseEntity<Shipment> mergeInto(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody MergeRequest req) {
        verifyOwnership(id, p.getMerchantId());
        verifyOwnership(req.shipmentIdB(), p.getMerchantId());
        return ResponseEntity.ok(shipmentService.merge(id, req.shipmentIdB(), p.getAccountId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Shipment> cancel(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody(required = false) CancelRequest req) {
        verifyOwnership(id, p.getMerchantId());
        String reason = req != null ? req.reason() : null;
        return ResponseEntity.ok(shipmentService.cancelShipment(id, reason, p.getAccountId()));
    }

    @PutMapping("/{id}/exception")
    public ResponseEntity<Shipment> raiseException(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ExceptionRequest req) {
        verifyOwnership(id, p.getMerchantId());
        ShipmentExceptionTypeEnum type = ShipmentExceptionTypeEnum.valueOf(req.exceptionType());
        return ResponseEntity.ok(
            shipmentService.raiseException(id, type, req.note(), p.getAccountId()));
    }

    @PutMapping("/{id}/exception/resolve")
    public ResponseEntity<Shipment> resolveException(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ResolveExceptionRequest req) {
        verifyOwnership(id, p.getMerchantId());
        ShipmentStatusEnum resume = ShipmentStatusEnum.fromCode(req.resumeStatus());
        return ResponseEntity.ok(shipmentService.resolveException(id, resume, p.getAccountId()));
    }

    @PutMapping("/{id}/dispatch")
    public ResponseEntity<Shipment> dispatch(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.dispatch(id, p.getAccountId()));
    }

    @PostMapping("/pick-list")
    public ResponseEntity<List<ShipmentService.PickLineItem>> pickList(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody PickListRequest req) {
        return ResponseEntity.ok(shipmentService.generatePickList(req.shipmentIds()));
    }

    @PostMapping("/sort-list")
    public ResponseEntity<List<ShipmentService.SortLineItem>> sortList(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody PickListRequest req) {
        return ResponseEntity.ok(shipmentService.generateSortList(req.shipmentIds()));
    }

    private void verifyOwnership(String shipmentId, String merchantId) {
        shipmentService.findById(shipmentId).ifPresentOrElse(s -> {
            if (!s.getMerchantId().equals(merchantId)) {
                throw new org.springframework.security.access.AccessDeniedException("Not your shipment");
            }
        }, () -> { throw new IllegalArgumentException("Shipment not found: " + shipmentId); });
    }
}
```

- [ ] **Step 2: Check UserPrincipal has getAccountId()**

```bash
grep -n "getAccountId\|accountId" /home/tom/ONEEC/simpleec-oms/simpleec-api/src/main/java/com/simpleec/api/security/UserPrincipal.java
```

If `getAccountId()` doesn't exist, use `getUsername()` instead in the controller — replace all `p.getAccountId()` with `p.getUsername()`.

- [ ] **Step 3: Build to verify**

```bash
./gradlew :simpleec-api:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add simpleec-api/src/main/java/com/simpleec/api/controller/UserShipmentController.java
git commit -m "feat: replace UserShipmentController — full shipment workflow API"
```

---

## Task 9: REST API — Batch Controller

**Files:**
- Create: `simpleec-api/src/main/java/com/simpleec/api/controller/UserShipmentBatchController.java`

- [ ] **Step 1: Add batch service methods to ShipmentService**

Add these to `ShipmentService`:

```java
// -----------------------------------------------------------------------
// Batch operations
// -----------------------------------------------------------------------

@Transactional
public ShipmentBatch createBatch(String merchantId, String carrier,
                                  LocalDateTime scheduledPickupAt, String notes) {
    ShipmentBatch batch = ShipmentBatch.builder()
        .id(NanoIdUtil.generate())
        .merchantId(merchantId)
        .batchNo("BATCH-" + LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")))
        .carrier(carrier)
        .scheduledPickupAt(scheduledPickupAt)
        .notes(notes)
        .status("PREPARING")
        .build();
    return shipmentBatchRepository.save(batch);
}

@Transactional
public ShipmentBatch forceReadyBatch(String batchId, String note) {
    ShipmentBatch batch = shipmentBatchRepository.findById(batchId)
        .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
    batch.setStatus("READY");
    batch.setForceReadyNote(note);
    return shipmentBatchRepository.save(batch);
}

@Transactional
public ShipmentBatch confirmPickup(String batchId, String carrierDriverId,
                                    int boxCount, java.math.BigDecimal cost) {
    ShipmentBatch batch = shipmentBatchRepository.findById(batchId)
        .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
    batch.setStatus("PICKED_UP");
    batch.setActualPickupAt(LocalDateTime.now());
    batch.setCarrierDriverId(carrierDriverId);
    batch.setHandoffBoxCount(boxCount);
    if (cost != null) batch.setLogisticsCost(cost);
    return shipmentBatchRepository.save(batch);
}

public Page<ShipmentBatch> findBatchesByMerchant(String merchantId, Pageable pageable) {
    return shipmentBatchRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
}

public Optional<ShipmentBatch> findBatchById(String id) {
    return shipmentBatchRepository.findById(id);
}

public List<Shipment> findShipmentsByBatch(String batchId) {
    return shipmentRepository.findByBatchId(batchId);
}

/** Generate manifest (裝車清單) for a batch. */
public Map<String, Object> generateManifest(String batchId) {
    ShipmentBatch batch = shipmentBatchRepository.findById(batchId)
        .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
    List<Shipment> shipments = shipmentRepository.findByBatchId(batchId);
    long activeCount = shipments.stream()
        .filter(s -> s.getStatus() != ShipmentStatusEnum.CANCELLED).count();
    List<Map<String, String>> boxes = shipments.stream()
        .filter(s -> s.getStatus() != ShipmentStatusEnum.CANCELLED)
        .map(s -> Map.of(
            "shipmentNo", s.getShipmentNo() != null ? s.getShipmentNo() : s.getId(),
            "trackingNumber", s.getTrackingNumber() != null ? s.getTrackingNumber() : "",
            "status", s.getStatus().getLabel()
        ))
        .collect(java.util.stream.Collectors.toList());

    // Use HashMap (not Map.of) because Map.of throws NPE on null values
    Map<String, Object> manifest = new java.util.HashMap<>();
    manifest.put("batchNo",    batch.getBatchNo());
    manifest.put("carrier",    batch.getCarrier() != null ? batch.getCarrier() : "");
    manifest.put("boxCount",   activeCount);
    manifest.put("scheduledPickupAt", batch.getScheduledPickupAt() != null
        ? batch.getScheduledPickupAt().toString() : null);
    manifest.put("generatedAt", LocalDateTime.now().toString());
    manifest.put("boxes",       boxes);
    return manifest;
}
```

- [ ] **Step 2: Create UserShipmentBatchController**

```java
package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Shipment;
import com.simpleec.core.entity.ShipmentBatch;
import com.simpleec.core.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/shipment-batches")
@RequiredArgsConstructor
public class UserShipmentBatchController {

    private final ShipmentService shipmentService;

    record CreateBatchRequest(String carrier, LocalDateTime scheduledPickupAt, String notes) {}
    record ForceReadyRequest(String note) {}
    record ConfirmPickupRequest(String carrierDriverId, int boxCount, BigDecimal logisticsCost) {}

    @PostMapping
    public ResponseEntity<ShipmentBatch> createBatch(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody CreateBatchRequest req) {
        ShipmentBatch batch = shipmentService.createBatch(
            p.getMerchantId(), req.carrier(), req.scheduledPickupAt(), req.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(batch);
    }

    @GetMapping
    public ResponseEntity<UserPageResponse<ShipmentBatch>> listBatches(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<ShipmentBatch> result = shipmentService.findBatchesByMerchant(
            p.getMerchantId(), PageRequest.of(page - 1, pageSize));
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBatch(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        return shipmentService.findBatchById(id)
            .filter(b -> b.getMerchantId().equals(p.getMerchantId()))
            .map(b -> {
                List<Shipment> shipments = shipmentService.findShipmentsByBatch(id);
                return ResponseEntity.ok(Map.of("batch", (Object) b, "shipments", shipments));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/force-ready")
    public ResponseEntity<ShipmentBatch> forceReady(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ForceReadyRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.forceReadyBatch(id, req.note()));
    }

    @PutMapping("/{id}/confirm-pickup")
    public ResponseEntity<ShipmentBatch> confirmPickup(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id,
            @RequestBody ConfirmPickupRequest req) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.confirmPickup(
            id, req.carrierDriverId(), req.boxCount(), req.logisticsCost()));
    }

    @GetMapping("/{id}/manifest")
    public ResponseEntity<Map<String, Object>> manifest(
            @AuthenticationPrincipal UserPrincipal p,
            @PathVariable String id) {
        verifyOwnership(id, p.getMerchantId());
        return ResponseEntity.ok(shipmentService.generateManifest(id));
    }

    private void verifyOwnership(String batchId, String merchantId) {
        shipmentService.findBatchById(batchId).ifPresentOrElse(b -> {
            if (!b.getMerchantId().equals(merchantId)) {
                throw new org.springframework.security.access.AccessDeniedException("Not your batch");
            }
        }, () -> { throw new IllegalArgumentException("Batch not found: " + batchId); });
    }
}
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew :simpleec-api:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add simpleec-core/src/main/java/com/simpleec/core/service/ShipmentService.java \
        simpleec-api/src/main/java/com/simpleec/api/controller/UserShipmentBatchController.java
git commit -m "feat: batch controller + manifest endpoint + batch service methods"
```

---

## Task 10: SHIP_ORDER v2 — Update ShipOrderHandler

**Files:**
- Modify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/handler/ShipOrderHandler.java`

- [ ] **Step 1: Read current ShipOrderHandler**

```bash
cat simpleec-channel-job/src/main/java/com/simpleec/channeljob/handler/ShipOrderHandler.java
```

- [ ] **Step 2: Update handler to read lineItems from v2 events**

First, add these imports to `ShipOrderHandler.java` if not already present:
```java
import java.util.ArrayList;
import java.util.List;
```

In `handleShipOrder()`, after extracting `body`:

```java
// Extract lineItems (v2 event — partial shipment support)
// v1: lineItems absent → pass empty string (Cyberbiz fulfills all items)
// v2: lineItems present → pass specific channel_item_ids
String lineItemIds = "";
if (body.has("lineItems") && body.get("lineItems").isArray()) {
    List<String> ids = new ArrayList<>();
    for (com.fasterxml.jackson.databind.JsonNode item : body.get("lineItems")) {
        String cid = item.path("channelItemId").asText(
            item.path("channel_item_id").asText(""));
        if (!cid.isBlank()) ids.add(cid);
    }
    lineItemIds = String.join(",", ids);
}
```

Replace the existing `lineItemIds` construction (currently hardcoded `""`) with this block.

- [ ] **Step 3: Build to verify**

```bash
./gradlew :simpleec-channel-job:build -x test 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Full build**

```bash
./gradlew clean build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add simpleec-channel-job/src/main/java/com/simpleec/channeljob/handler/ShipOrderHandler.java
git commit -m "feat: ShipOrderHandler — support SHIP_ORDER v2 lineItems for partial shipment dispatch"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|-----------------|------|
| Replace `order_shipments` with Flyway V2 | Task 1 |
| `PARTIALLY_SHIPPED` OrderStatusEnum | Task 2 |
| Shipment/ShipmentItem/ShipmentBatch/ShipmentStatusLog entities | Task 3 |
| createShipments (Phase 1) | Task 4 |
| advanceStatus, cancel, ON_HOLD | Task 4 |
| Split with Redis lock | Task 5 |
| Merge with Redis lock | Task 5 |
| 拿貨清單 / 分貨清單 pick lists | Task 6 |
| Dispatch (atomic DB + Kafka v2) | Task 7 |
| REST API (all endpoints) | Task 8 |
| Batch API + manifest | Task 9 |
| SHIP_ORDER v2 lineItems | Task 10 |
| CVS preparatory fields | Task 1 (schema), Task 3 (entity) ✅ (nullable) |
| COD fields | Task 1 (schema), Task 3 (entity) ✅ (nullable) |
| `merchant_options` for batch_mode | spec documents as INSERT, not in plan — no code needed until batch mode toggle UI is built |
| Batch auto-READY on all LABELING | Task 4 `checkAndAutoReadyBatch` ✅ |
| Force-READY override | Task 9 ✅ |

All spec requirements covered. No placeholders or TBDs.

---

## Post-Review Fixes Applied (2026-03-29 Team Review)

The following issues were found by PM + Architect + Developer review and fixed inline:

| Severity | Issue | Fix Applied |
|----------|-------|------------|
| ❌ Fatal | `@Transactional` self-invocation in `dispatch()` — no transaction would start | Changed to `TransactionTemplate.execute()` |
| ❌ Fatal | Kafka topic used `channelId + ".fast"` — channel IDs are NanoIDs, not platform names | Inject `ChannelRepository`, use `channel.getPlatformId().toLowerCase() + ".fast"` (same as SchedulerEventHandler) |
| ❌ Fatal | `UserOrderController` imports deleted `OrderShipment` + `OrderShipmentRepository` — build failure | Added Task 3 Step 6b to update UserOrderController; added to File Map |
| ❌ Bug | `merge()` captured `b.getStatus()` after `b.setStatus(CANCELLED)` — audit log corrupted | Capture `prevB = b.getStatus()` before mutation |
| ❌ Bug | `findActiveByOrderId` used JPQL JOIN on plain String FK — would fail at runtime | Replaced with native SQL query (`nativeQuery = true`) |
| ⚠️ Concurrency | Redis lock used hardcoded `"1"` value — unsafe delete after TTL expiry | Use `UUID.randomUUID()` as lock value; check ownership before delete |
| ⚠️ Concurrency | `merge()` lock cleanup deleted other thread's lock when own lock not acquired | Only delete locks that were actually acquired (`Boolean.TRUE.equals(lockedX)`) |
| ⚠️ NPE | `generateManifest()` used `Map.of()` with nullable `scheduledPickupAt` | Replaced with `HashMap` (tolerates null values) |
| ⚠️ Data quality | `platform_notified_at` save in `whenComplete` had no transaction context | Wrapped in `transactionTemplate.executeWithoutResult()` |
| ⚠️ API | `createShipments` accepted `channelId` from request body — security gap | Remove `channelId` param; derive from `order.getChannelId()` |
| ⚠️ API | `shipmentNo` sequence reset per call — collision risk on same day | Use NanoID suffix instead of `seq++` |
| ⚠️ API | Dead `POST /merge` stub threw `UnsupportedOperationException` | Removed dead stub; `POST /{id}/merge` is the only merge endpoint |
| ℹ️ Task 10 | Missing `ArrayList`/`List` imports in ShipOrderHandler step | Added explicit import instruction |
| ℹ️ Info | PM raised missing "ready to ship" endpoint | `GET /api/user/orders?status=READY_TO_SHIP` already exists; noted in Step 6b |
| ❌ Bug | `DISPATCHED` in `STATUS_SEQUENCE` — operator could call `advanceStatus()` from `AWAITING_PICKUP` and reach `DISPATCHED` silently (no Kafka, no order update) | Removed `DISPATCHED` from `STATUS_SEQUENCE`; added comment explaining intent; `advanceStatus()` now throws at `AWAITING_PICKUP` to force use of `dispatch()` |
| ⚠️ Safety | `countBatchShipmentsNotLabeled`/`countActiveBatchShipments` missing `@Param("batchId")` | Added `@Param` annotation (consistent with rest of codebase; required if `-parameters` compiler flag absent) |
| ℹ️ Git | Task 3 Step 8 `git add` omitted `UserOrderController.java` — Step 6b changes would not be committed | Added `simpleec-api/.../UserOrderController.java` to the `git add` command |
| ❌ Bug | `dispatchDb` loop iterates all ShipmentItem rows; after split+merge, same orderId can appear twice → SHIP_ORDER published twice for that order | Added `Set<String> processedOrderIds` deduplication; `if (!processedOrderIds.add(...)) continue` before processing each row |
| ❌ Bug | `02-seed-data.sql` still has `INSERT INTO public.order_shipments` — fresh `docker compose up` fails after Task 1 removes that table from `01-schema.sql` | Added explicit note to Task 1 Step 2 to remove those rows; added `02-seed-data.sql` to Task 1 Step 4 commit |
| ❌ Bug | `buildInitialItemsJson` reads `item.path("name")` — field not present in `orders.items` (stored as `productName`) → all shipment item names blank | Fixed to `item.path("productName").asText(item.path("name").asText(""))` |

**Remaining deferred items (not plan bugs — future features):**
- No cascade cancellation when platform cancels an order → tracked as follow-up ticket
- No reconciliation job for `platform_notified_at IS NULL` → mark for post-launch sprint
- Batch mode `merchant_options` enforcement in dispatch → add when batch mode toggle UI is built
- Batch COMPLETED status transition → add with reconciliation job sprint
