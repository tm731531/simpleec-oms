# 退貨流程完整設計

**最後更新**: 2026-02-25
**基於**: 訂單處理架構的擴展
**狀態**: 設計階段（待實現）

---

## 📋 目錄

1. [核心概念](#核心概念)
2. [完整數據流](#完整數據流)
3. [系統架構](#系統架構)
4. [狀態機](#狀態機)
5. [API 設計](#api-設計)
6. [實現細節](#實現細節)

---

## 核心概念

### 退貨 vs 退款 vs 返貨

| 術語 | 定義 | 流程 | 時間 |
|------|------|------|------|
| **返貨 (Return)** | 買家不滿意，要求退貨 | 發起 → 審核 → 回收 → 檢查 → 同意退款 | 7-14 天 |
| **退款 (Refund)** | 退回貨款 | 確認退貨完成 → 處理退款 → 打回銀行帳戶 | 1-5 天 |
| **換貨 (Exchange)** | 換同價格或更高價格商品 | 返貨 → 檢查 → 發送新商品 | 7-21 天 |

本文檔關注 **Return Flow**（返貨流程）。

---

## 完整數據流

### 訂單完整生命週期

```
┌─────────────┐
│ 訂單建立     │  PENDING
│ (PENDING)    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 訂單確認     │  CONFIRMED
│ (CONFIRMED)  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 準備出貨     │  READY_TO_SHIP
│ (READY)      │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 出貨中       │  SHIPPING
│ (SHIPPING)   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 已出貨       │  SHIPPED
│ (SHIPPED)    │
└──────┬──────┘
       │
       ├─────→ 買家不滿意，申請返貨
       │         │
       │         ▼
       │    ┌──────────────────┐
       │    │ 返貨申請         │  RETURN_PENDING
       │    │ (RETURN_PENDING) │
       │    └────────┬─────────┘
       │             │
       │    ┌────────▼──────────────┐
       │    │ 需要決定: 同意還是拒絕 │
       │    └────────┬─────────────┘
       │             │
       │    ┌────────┴─────────┐
       │    │                  │
       │    ▼                  ▼
       │  同意               拒絕
       │  │                  │
       │  ▼                  ▼
       │  ┌───────────────┐  ┌──────────────┐
       │  │ 返貨進行中    │  │ 返貨被拒絕   │
       │  │ (RETURNING)   │  │ (REJECTED)   │
       │  └───────┬───────┘  └──────────────┘
       │          │
       │          ▼
       │     ┌────────────────────┐
       │     │ 買家寄回商品       │
       │     │ (GOODS_RECEIVED)   │
       │     └────────┬───────────┘
       │              │
       │              ▼
       │     ┌────────────────────┐
       │     │ 檢查貨物品質       │
       │     │ (INSPECTED)        │
       │     └────────┬───────────┘
       │              │
       │     ┌────────┴──────────┐
       │     │                   │
       │     ▼                   ▼
       │   符合               不符合
       │   │                 │
       │   ▼                 ▼
       │ ┌─────────────┐   ┌──────────────┐
       │ │ 同意退款    │   │ 不同意退款   │
       │ │ (ACCEPTED)  │   │ (REJECTED)   │
       │ └─────┬───────┘   └──────────────┘
       │       │
       │       ▼
       │   ┌───────────────┐
       │   │ 處理退款      │
       │   │ (REFUNDING)   │
       │   └───────┬───────┘
       │           │
       │           ▼
       │       ┌────────────────┐
       │       │ 退款完成       │
       │       │ (REFUNDED)     │
       │       └────────────────┘
       │
       ▼
┌─────────────┐
│ 訂單完成     │  COMPLETED
│ (COMPLETED) │
└─────────────┘
```

---

## 系統架構

### 三個獨立的子流程

#### 1. 返貨申請流程 (Return Application)

**職責**: 買家發起 → 商家審核 → 退款決定

```
Frontend (User App)
    │
    ├─ POST /api/user/returns             (發起返貨申請)
    │
Nginx (Port 8089)
    │
Backend API (simpleec-api)
    │
    ├─ POST /api/returns                  (儲存申請)
    ├─ Kafka: RETURN_CREATED event
    │
Backend Job (simpleec-backend-job)
    │
    ├─ Consumer: task.backend topic
    ├─ Handler: ReturnCreatedHandler
    ├─ Action: 發送通知給商家、寄送回郵標籤
    │
Frontend (Admin App)
    │
    └─ GET /api/admin/returns             (查看申請)
       POST /api/admin/returns/{id}/approve  (同意)
       POST /api/admin/returns/{id}/reject   (拒絕)
```

#### 2. 返貨物流流程 (Return Logistics)

**職責**: 買家寄回商品 → 商家收貨 → 檢查品質

```
Logistics System (外部)
    │
    ├─ 買家寄出 (GOODS_SHIPPED)
    │
Webhook / Integration
    │
    ├─ POST /api/webhook/logistics/tracking
    │
Kafka: GOODS_RETURNED event
    │
Backend Job (simpleec-backend-job)
    │
    ├─ Handler: GoodsReturnedHandler
    ├─ Action: 更新返貨狀態、通知商家
    │
商家 (Admin App)
    │
    └─ 檢查並確認貨物完整性
       POST /api/admin/returns/{id}/inspect
```

#### 3. 退款流程 (Refund Processing)

**職責**: 確認返貨 → 處理退款 → 驗證到帳

```
商家確認檢查結果
    │
    ├─ 同意退款: 觸發 REFUND_APPROVED event
    │
Kafka: REFUND_APPROVED
    │
Backend Job (simpleec-backend-job)
    │
    ├─ Handler: RefundApprovedHandler
    ├─ Action: 調用支付接口退款、記錄交易
    │
Payment Gateway
    │
    ├─ 處理退款邏輯 (支付寶、PayPal 等)
    │
Webhook: 退款結果
    │
Kafka: REFUND_COMPLETED event
    │
Frontend (User App)
    │
    └─ 顯示退款成功，買家收到錢
```

---

## 狀態機

### Return 狀態枚舉

```java
enum ReturnStatusEnum {
    // 申請階段
    PENDING("PENDING", "待審核", "買家已申請，等待商家審核"),
    APPROVED("APPROVED", "已同意", "商家同意返貨"),
    REJECTED("REJECTED", "已拒絕", "商家拒絕返貨申請"),

    // 物流階段
    GOODS_SHIPPED("GOODS_SHIPPED", "買家已寄出", "買家已寄回商品"),
    GOODS_RECEIVED("GOODS_RECEIVED", "已收貨", "商家已收到商品"),
    INSPECTED("INSPECTED", "已檢查", "商家檢查完成"),

    // 退款階段
    REFUND_APPROVED("REFUND_APPROVED", "已批准退款", "商家同意退款"),
    REFUND_REJECTED("REFUND_REJECTED", "已拒絕退款", "商家拒絕退款"),
    REFUNDING("REFUNDING", "退款中", "正在處理退款"),
    REFUNDED("REFUNDED", "已退款", "退款已完成，買家已收到");

    private final String code;
    private final String label;
    private final String description;
}
```

### 狀態轉移規則

```java
public class ReturnStateTransition {

    public static boolean isValidTransition(
        ReturnStatusEnum from,
        ReturnStatusEnum to
    ) {
        return switch (from) {
            // PENDING (申請中)
            case PENDING -> to == APPROVED || to == REJECTED;

            // APPROVED (已同意)
            case APPROVED -> to == GOODS_SHIPPED || to == GOODS_RECEIVED;

            // GOODS_SHIPPED (買家已寄出)
            case GOODS_SHIPPED -> to == GOODS_RECEIVED || to == PENDING;  // 可退回

            // GOODS_RECEIVED (已收貨)
            case GOODS_RECEIVED -> to == INSPECTED;

            // INSPECTED (已檢查)
            case INSPECTED -> to == REFUND_APPROVED || to == REFUND_REJECTED;

            // REFUND_APPROVED (已批准退款)
            case REFUND_APPROVED -> to == REFUNDING;

            // REFUNDING (退款中)
            case REFUNDING -> to == REFUNDED;

            // REJECTED 和 REFUNDED 是終態
            case REJECTED, REFUND_REJECTED, REFUNDED -> false;

            default -> false;
        };
    }
}
```

---

## API 設計

### 用戶端 API (simpleec-api)

```java
// ==================== 返貨申請 ====================

// 1️⃣ 發起返貨申請
POST /api/user/returns
{
    "orderId": "ord_123",
    "reason": "商品破損",
    "description": "收到時外盒破損，內盒碎裂",
    "images": ["url1", "url2"]  // 證明照片
}
→ {
    "returnId": "ret_456",
    "status": "PENDING",
    "returnLabel": "https://..."  // 回郵標籤
}

// 2️⃣ 查詢返貨申請詳情
GET /api/user/returns/{returnId}
→ {
    "returnId": "ret_456",
    "orderId": "ord_123",
    "status": "PENDING",
    "reason": "商品破損",
    "createdAt": "2026-02-25T10:00:00Z",
    "approvedAt": null,
    "timeline": [
        {
            "status": "PENDING",
            "timestamp": "2026-02-25T10:00:00Z",
            "message": "申請已提交"
        },
        ...
    ]
}

// 3️⃣ 追蹤返貨物流
GET /api/user/returns/{returnId}/tracking
→ {
    "status": "GOODS_SHIPPED",
    "trackingNumber": "1Z999AA...",
    "carrier": "UPS",
    "estimatedDelivery": "2026-02-28"
}

// ==================== 管理端 API ====================

// 4️⃣ 查詢所有返貨申請 (分頁、篩選)
GET /api/admin/returns?status=PENDING&page=0&size=20
→ {
    "data": [
        {
            "returnId": "ret_456",
            "orderId": "ord_123",
            "buyerName": "張三",
            "reason": "商品破損",
            "status": "PENDING",
            "createdAt": "2026-02-25T10:00:00Z"
        }
    ],
    "total": 42,
    "page": 0,
    "size": 20
}

// 5️⃣ 同意返貨申請
POST /api/admin/returns/{returnId}/approve
{
    "approvalMessage": "已同意，請使用回郵標籤"
}
→ {
    "status": "APPROVED",
    "returnLabel": "https://..."
}

// 6️⃣ 拒絕返貨申請
POST /api/admin/returns/{returnId}/reject
{
    "reason": "申請超時",
    "message": "返貨申請已過期"
}
→ {
    "status": "REJECTED"
}

// 7️⃣ 確認已收貨
POST /api/admin/returns/{returnId}/receive
{
    "receivedAt": "2026-02-28T14:00:00Z"
}
→ {
    "status": "GOODS_RECEIVED"
}

// 8️⃣ 檢查並決定退款
POST /api/admin/returns/{returnId}/inspect
{
    "inspectionResult": "APPROVED",  // APPROVED 或 REJECTED
    "inspectionNotes": "商品完整，已批准退款",
    "damagePercentage": 0
}
→ {
    "status": "INSPECTED",
    "refundAmount": 1000
}

// 9️⃣ 處理退款
POST /api/admin/returns/{returnId}/refund
{
    "refundMethod": "ORIGINAL_PAYMENT"  // ORIGINAL_PAYMENT 或 STORE_CREDIT
}
→ {
    "status": "REFUNDING",
    "refundId": "ref_789",
    "refundAmount": 1000
}
```

---

## 實現細節

### 1. 數據庫表設計

```sql
-- 返貨主表
CREATE TABLE returns (
    id VARCHAR(20) PRIMARY KEY,
    order_id VARCHAR(20) NOT NULL,
    merchant_id VARCHAR(20) NOT NULL,
    buyer_name VARCHAR(100),
    return_reason TEXT,
    return_description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    refund_amount DECIMAL(10, 2),
    return_label_url TEXT,
    tracking_number VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- 返貨歷史 (狀態變更記錄)
CREATE TABLE return_history (
    id VARCHAR(20) PRIMARY KEY,
    return_id VARCHAR(20) NOT NULL,
    old_status VARCHAR(50),
    new_status VARCHAR(50),
    changed_by VARCHAR(100),
    change_reason TEXT,
    changed_at TIMESTAMP DEFAULT NOW(),
    FOREIGN KEY (return_id) REFERENCES returns(id)
);

-- 退款記錄
CREATE TABLE refunds (
    id VARCHAR(20) PRIMARY KEY,
    return_id VARCHAR(20) NOT NULL,
    order_id VARCHAR(20) NOT NULL,
    refund_amount DECIMAL(10, 2) NOT NULL,
    refund_method VARCHAR(50),  -- ORIGINAL_PAYMENT, STORE_CREDIT
    payment_gateway_id VARCHAR(100),
    status VARCHAR(50),  -- PENDING, PROCESSING, COMPLETED, FAILED
    created_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    FOREIGN KEY (return_id) REFERENCES returns(id),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

### 2. Kafka 事件

```json
// 1. 返貨申請建立
{
  "header": {
    "taskType": "RETURN_CREATED",
    "source": "api"
  },
  "body": {
    "returnId": "ret_456",
    "orderId": "ord_123",
    "reason": "商品破損"
  }
}

// 2. 返貨已批准
{
  "header": {
    "taskType": "RETURN_APPROVED",
    "source": "admin"
  },
  "body": {
    "returnId": "ret_456",
    "approvedAt": "2026-02-25T11:00:00Z"
  }
}

// 3. 買家已寄出
{
  "header": {
    "taskType": "GOODS_RETURNED",
    "source": "logistics_webhook"
  },
  "body": {
    "returnId": "ret_456",
    "trackingNumber": "1Z999AA...",
    "carrier": "UPS"
  }
}

// 4. 商家已檢查
{
  "header": {
    "taskType": "RETURN_INSPECTED",
    "source": "admin"
  },
  "body": {
    "returnId": "ret_456",
    "inspectionResult": "APPROVED",
    "refundAmount": 1000
  }
}

// 5. 退款已批准
{
  "header": {
    "taskType": "REFUND_APPROVED",
    "source": "admin"
  },
  "body": {
    "returnId": "ret_456",
    "refundId": "ref_789",
    "refundAmount": 1000
  }
}

// 6. 退款完成
{
  "header": {
    "taskType": "REFUND_COMPLETED",
    "source": "payment_webhook"
  },
  "body": {
    "refundId": "ref_789",
    "status": "COMPLETED",
    "completedAt": "2026-02-28T15:00:00Z"
  }
}
```

### 3. Handler 實現

```java
@Slf4j
@Component
public class ReturnEventHandlers {

    private final ReturnService returnService;
    private final NotificationService notificationService;

    // 返貨申請建立
    @KafkaListener(topics = "task.backend")
    public void handleReturnCreated(String message) {
        ReturnCreatedEvent event = parse(message);
        if (event.getTaskType().equals("RETURN_CREATED")) {
            // 1. 儲存返貨記錄
            Return returnRecord = returnService.createReturn(event);

            // 2. 生成回郵標籤
            String returnLabel = logisticsService.generateReturnLabel(
                returnRecord.getOrderId()
            );

            // 3. 發送通知
            notificationService.notifyBuyer(
                "退貨已申請",
                "請使用回郵標籤寄回商品",
                returnLabel
            );
            notificationService.notifyMerchant(
                "新的退貨申請",
                event.getReason()
            );
        }
    }

    // 商家檢查完成
    public void handleReturnInspected(String message) {
        ReturnInspectedEvent event = parse(message);
        Return returnRecord = returnService.getReturn(event.getReturnId());

        if (event.getInspectionResult().equals("APPROVED")) {
            // 批准退款
            Refund refund = refundService.createRefund(
                returnRecord.getOrderId(),
                event.getRefundAmount()
            );

            // 發送 REFUND_APPROVED 事件
            kafkaTemplate.send("task.backend",
                new RefundApprovedEvent(refund.getId())
            );
        } else {
            // 拒絕退款
            returnService.updateStatus(
                returnRecord.getId(),
                ReturnStatusEnum.REFUND_REJECTED
            );
        }
    }

    // 退款完成
    public void handleRefundCompleted(String message) {
        RefundCompletedEvent event = parse(message);
        Refund refund = refundService.getRefund(event.getRefundId());

        // 更新返貨狀態
        returnService.updateStatus(
            refund.getReturnId(),
            ReturnStatusEnum.REFUNDED
        );

        // 發送通知
        notificationService.notifyBuyer(
            "退款已完成",
            "您的 " + refund.getRefundAmount() + " 元已原路返回",
            null
        );
    }
}
```

---

## 下一步

### 優先級排序

1. **Phase 1** (本週): API 設計審批，數據庫表設計
2. **Phase 2** (下週): Handler 實現，基本流程測試
3. **Phase 3** (第 3 周): 前端集成，完整 E2E 測試
4. **Phase 4** (第 4 周): 生產發佈準備

### 相關文件

- [訂單處理完整架構](./ORDER_PROCESS_ARCHITECTURE.md)
- [新平台集成指南](./NEW_PLATFORM_INTEGRATION_GUIDE.md)

---

**預計工作量**: 5-7 天
**難度等級**: ⭐⭐⭐⭐ (較難，涉及複雜狀態機)
**經驗要求**: 完整的 Kafka, Spring Boot, 業務流程理解
