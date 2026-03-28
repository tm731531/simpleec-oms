# User API Endpoint

所有用戶端 endpoint 都需要有效的 Bearer JWT token，除非標注為公開。

**Base URL：** `http://localhost:8082`

**認證 header：**
```
Authorization: Bearer <token>
```

所有資料都依照 JWT 中編碼的商家範疇隔離。商家 A 的用戶永遠無法讀取或修改商家 B 的資料。

---

## 目錄

- [認證](#認證)
  - [POST /api/auth/login](#post-apiauthlogin)
  - [GET /api/auth/me](#get-apiauthme)
  - [POST /api/auth/logout](#post-apiauthlogout)
- [訂單](#訂單)
  - [GET /api/user/orders](#get-apiuserorders)
  - [POST /api/user/orders](#post-apiuserorders)
  - [GET /api/user/orders/{id}](#get-apiuserordersid)
  - [PATCH /api/user/orders/{id}](#patch-apiuserordersid)
  - [GET /api/user/orders/{id}/shipments](#get-apiuserordersidshipments)
  - [GET /api/user/orders/{id}/status-logs](#get-apiuserordersidstatus-logs)
- [出貨](#出貨)
  - [GET /api/user/shipments](#get-apiusershipments)
  - [POST /api/user/shipments](#post-apiusershipments)
  - [GET /api/user/shipments/{id}](#get-apiusershipmentsid)
  - [PATCH /api/user/shipments/{id}](#patch-apiusershipmentsid)
  - [GET /api/user/shipments/by-order/{orderId}](#get-apiusershipmentsby-orderorderid)
- [退款](#退款)
  - [GET /api/user/refunds](#get-apiuserrefunds)
  - [POST /api/user/refunds](#post-apiuserrefunds)
  - [GET /api/user/refunds/{id}](#get-apiuserrefundsid)
  - [PATCH /api/user/refunds/{id}](#patch-apiuserrefundsid)
- [庫存](#庫存)
  - [GET /api/user/inventory](#get-apiuserinventory)
  - [GET /api/user/inventory/low-stock](#get-apiuserinventorylow-stock)
  - [PATCH /api/user/inventory/{productId}](#patch-apiuserinventoryproductid)
- [報表](#報表)
  - [GET /api/user/reports/sales](#get-apiuserreportssales)
  - [GET /api/user/reports/profit](#get-apiuserreportsprofit)
- [統計](#統計)
  - [GET /api/user/stats/daily](#get-apiuserstatsdaily)
  - [GET /api/user/stats/today](#get-apiuserstatstoday)
- [Enum](#enum)
  - [GET /api/enums/order-statuses](#get-apienumsorder-statuses)
- [健康檢查](#健康檢查)
  - [GET /api/health](#get-apihealth)
  - [GET /api/version](#get-apiversion)

---

## 認證

### POST /api/auth/login

以 email 和密碼進行認證。回傳有效期 7 天的 JWT。

**認證：** 不需要。

**請求 Body：**

| 欄位       | 型別   | 必填 | 說明         |
|------------|--------|------|--------------|
| `email`    | String | 是   | 帳號 email   |
| `password` | String | 是   | 帳號密碼     |

```bash
curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "owner@acme-shop.com",
    "password": "secret123"
  }'
```

**回應（200 OK）：**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJWNG5LcThtUjJ4THBZb1p3MUEzQiIsIm1lcmNoYW50SWQiOiJhMDAwMDAiLCJlbWFpbCI6Im93bmVyQGFjbWUtc2hvcC5jb20iLCJuYW1lIjoiQWxpY2UgQ2hlbiIsInJvbGUiOiJtYWluIiwiaWF0IjoxNzQzMTIwMDAwLCJleHAiOjE3NDM3MjQ4MDB9.SIG",
  "user": {
    "id": "V4nKq8mR2xLpYoZw1A3B",
    "email": "owner@acme-shop.com",
    "name": "Alice Chen",
    "merchantId": "a00000",
    "merchantName": "ACME Shop",
    "role": "main"
  }
}
```

**錯誤回應：** `400` 缺少欄位，`401` 憑證錯誤，`403` 帳號已停用。

---

### GET /api/auth/me

回傳從 JWT 解碼後的當前認證用戶身份資訊。

**認證：** 需要 Bearer token。

```bash
curl -s http://localhost:8082/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
{
  "id": "V4nKq8mR2xLpYoZw1A3B",
  "email": "owner@acme-shop.com",
  "name": "Alice Chen",
  "merchantId": "a00000",
  "role": "main"
}
```

**錯誤回應：** `401` 若無有效 token。

---

### POST /api/auth/logout

無狀態登出。由於 JWT 不在伺服器端追蹤，此 endpoint 永遠成功。用戶端負責丟棄 token。

**認證：** Bearer token（可選，無論是否提供均回傳 200）。

```bash
curl -s -X POST http://localhost:8082/api/auth/logout \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：** 空 body。

---

## 訂單

### GET /api/user/orders

已認證商家的訂單分頁列表。支援依通路或狀態過濾。

**認證：** 需要 Bearer token。

**Query 參數：**

| 參數        | 型別    | 預設值 | 說明                                                             |
|-------------|---------|--------|------------------------------------------------------------------|
| `page`      | Integer | `1`    | 頁碼（從 1 開始）。最小值：1。                                   |
| `pageSize`  | Integer | `10`   | 每頁筆數。最小值：1。最大值：100。                               |
| `status`    | String  | —      | 依訂單狀態碼過濾（例如 `pending`、`shipped`）。                  |
| `channelId` | String  | —      | 依通路 ID 過濾。與 `status` 互斥（`status` 優先）。              |

```bash
# 全部訂單，第 1 頁
curl -s "http://localhost:8082/api/user/orders" \
  -H "Authorization: Bearer $TOKEN"

# 依狀態過濾
curl -s "http://localhost:8082/api/user/orders?status=pending&page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"

# 依通路過濾
curl -s "http://localhost:8082/api/user/orders?channelId=CHANNEL_SHOPEE_001&page=1&pageSize=25" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）— UserPageResponse：**
```json
{
  "data": [
    {
      "id": "Kp7mNqR3xVoWyZj2B5Lc",
      "merchantId": "a00000",
      "orderNumber": "2024031500001",
      "platform": "Shopee",
      "status": "pending",
      "totalAmount": 1250.00,
      "createdAt": "2026-03-15T08:30:00",
      "updatedAt": "2026-03-15T08:30:00",
      "syncStatus": null,
      "channelId": "CHANNEL_SHOPEE_001",
      "channelOrderId": "250315123456",
      "channelOrderNumber": "2024031500001",
      "orderStatus": "pending",
      "shippingFee": 60.00,
      "discountAmount": 0.00,
      "buyerName": "王小明",
      "buyerPhone": "0912345678",
      "buyerEmail": "buyer@example.com",
      "items": [
        {
          "channelItemId": "ITEM_001",
          "productName": "Premium Cotton T-Shirt",
          "quantity": 2,
          "unitPrice": 595.00
        }
      ],
      "buyerInfo": {},
      "shippingInfo": {
        "address": "台北市中山路 123 號"
      },
      "paymentMethod": "credit_card",
      "shippingMethod": "home_delivery",
      "paidAt": "2026-03-15T08:25:00",
      "shippedAt": null,
      "channelCreatedAt": "2026-03-15T08:20:00",
      "isRollback": false
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 142,
    "pages": 15
  }
}
```

---

### POST /api/user/orders

透過 API 提交訂單。訂單發布至 `order.process` Kafka topic，並透過標準事件管線非同步處理（OrderUpsertConsumer → DB → 統計）。

**認證：** 需要 Bearer token。

**請求 Body：**

| 欄位               | 型別   | 必填 | 說明                                           |
|--------------------|--------|------|------------------------------------------------|
| `channelId`        | String | 是   | 必須屬於您的商家的通路 ID                      |
| `channelOrderId`   | String | 是   | 平台的訂單識別碼（每個通路內唯一）             |
| `channelOrderNumber`| String | 否  | 平台的易讀訂單編號                             |
| `orderStatus`      | String | 否   | 初始狀態碼                                     |
| `totalAmount`      | Number | 否   | 訂單總金額                                     |
| `...`              | Any    | 否   | 其他傳入 orderData 的訂單欄位                  |

```bash
curl -s -X POST http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "channelId": "CHANNEL_SHOPEE_001",
    "channelOrderId": "250315999001",
    "channelOrderNumber": "SHP-2026031501",
    "orderStatus": "pending",
    "totalAmount": 890.00,
    "shippingFee": 60.00,
    "buyerName": "林美華",
    "paymentMethod": "cod"
  }'
```

**回應（202 Accepted）：**
```json
{
  "channelOrderId": "250315999001",
  "status": "accepted"
}
```

`202 Accepted` 表示訊息已發布至 Kafka。訂單在 consumer 處理後（通常在 1-2 秒內）就會出現於 `GET /api/user/orders`。

**錯誤回應：** `400` 若缺少 `channelId` 或 `channelOrderId`，或找不到通路。`403` 若通路不屬於您的商家。

---

### GET /api/user/orders/{id}

依內部 OMS ID 查詢單一訂單。PII 欄位（buyerName、buyerPhone、buyerEmail、shippingInfo）在回傳前會解密。

**認證：** 需要 Bearer token。

**路徑參數：**

| 參數  | 型別   | 說明                          |
|-------|--------|-------------------------------|
| `id`  | String | 內部 OMS 訂單 ID（NanoID）    |

```bash
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
{
  "id": "Kp7mNqR3xVoWyZj2B5Lc",
  "merchantId": "a00000",
  "channelId": "CHANNEL_SHOPEE_001",
  "channelOrderId": "250315123456",
  "channelOrderNumber": "2024031500001",
  "orderStatus": "PENDING",
  "totalAmount": 1250.00,
  "shippingFee": 60.00,
  "discountAmount": 0.00,
  "buyerName": "王小明",
  "buyerPhone": "0912345678",
  "buyerEmail": "buyer@example.com",
  "shippingInfo": {
    "address": "台北市中山路 123 號"
  },
  "items": [...],
  "paymentMethod": "credit_card",
  "shippingMethod": "home_delivery",
  "paidAt": "2026-03-15T08:25:00",
  "shippedAt": null,
  "channelCreatedAt": "2026-03-15T08:20:00",
  "createdAt": "2026-03-15T08:30:00",
  "updatedAt": "2026-03-15T08:30:00",
  "isRollback": false
}
```

**錯誤回應：** `404` 若訂單不存在或屬於不同商家。

---

### PATCH /api/user/orders/{id}

對訂單執行操作。支援的操作：`ship`（標記已出貨，並將 SHIP_ORDER 發布至平台的 fast topic）和 `cancel`（標記已取消，並將 CANCEL_ORDER_INTERNAL 發布至 order.process）。

**認證：** 需要 Bearer token。

**路徑參數：**

| 參數  | 型別   | 說明                          |
|-------|--------|-------------------------------|
| `id`  | String | 內部 OMS 訂單 ID（NanoID）    |

**請求 Body — 出貨：**

| 欄位            | 型別   | 必填 | 說明                   |
|-----------------|--------|------|------------------------|
| `action`        | String | 是   | 必須為 `"ship"`        |
| `trackingNumber`| String | 否   | 物流追蹤號碼           |
| `carrier`       | String | 否   | 物流公司名稱           |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "action": "ship",
    "trackingNumber": "799110012345678",
    "carrier": "BlackCat"
  }'
```

**請求 Body — 取消：**

| 欄位     | 型別   | 必填 | 說明           |
|----------|--------|------|----------------|
| `action` | String | 是   | 必須為 `"cancel"` |
| `reason` | String | 否   | 取消原因       |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"cancel","reason":"Customer requested cancellation"}'
```

**回應（200 OK）：** 更新後的訂單實體（結構與 GET /api/user/orders/{id} 相同）。

**錯誤回應：** `400` 若 action 不是 `ship` 或 `cancel`。`404` 若訂單不存在或屬於不同商家。

---

### GET /api/user/orders/{id}/shipments

列出特定訂單下的所有出貨記錄。

**認證：** 需要 Bearer token。

```bash
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc/shipments" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
[
  {
    "id": "Sh9pQr4tUvXwYzA2C6Df",
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "trackingNumber": "799110012345678",
    "logisticsCompany": "BlackCat",
    "shippingStatus": "pending",
    "shippedAt": "2026-03-16T10:00:00",
    "createdAt": "2026-03-16T10:00:00",
    "updatedAt": "2026-03-16T10:00:00"
  }
]
```

**錯誤回應：** `404` 若訂單不存在或屬於不同商家。

---

### GET /api/user/orders/{id}/status-logs

取得訂單的完整狀態變更歷史，依時間由舊到新排序。可用於在 UI 中渲染時間軸。

**認證：** 需要 Bearer token。

```bash
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc/status-logs" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
[
  {
    "id": "Lg2nMo5pQrStUv7wXyZa",
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "fromStatus": null,
    "toStatus": "pending",
    "changedAt": "2026-03-15T08:30:00",
    "source": "channel_job",
    "createdAt": "2026-03-15T08:30:01"
  },
  {
    "id": "Bc3dEf6gHiJkLm8nOpQr",
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "fromStatus": "pending",
    "toStatus": "shipped",
    "changedAt": "2026-03-16T10:00:00",
    "source": "api",
    "createdAt": "2026-03-16T10:00:01"
  }
]
```

**錯誤回應：** `404` 若訂單不存在或屬於不同商家。

---

## 出貨

### GET /api/user/shipments

已認證商家的所有出貨記錄分頁列表，依建立時間降冪排序。

**認證：** 需要 Bearer token。

**Query 參數：**

| 參數       | 型別    | 預設值 | 說明               |
|------------|---------|--------|--------------------|
| `page`     | Integer | `1`    | 頁碼（從 1 開始）  |
| `pageSize` | Integer | `10`   | 每頁筆數           |

```bash
curl -s "http://localhost:8082/api/user/shipments?page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）— UserPageResponse：**
```json
{
  "data": [
    {
      "id": "Sh9pQr4tUvXwYzA2C6Df",
      "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
      "trackingNumber": "799110012345678",
      "logisticsCompany": "BlackCat",
      "shippingStatus": "pending",
      "shippedAt": "2026-03-16T10:00:00",
      "createdAt": "2026-03-16T10:00:00",
      "updatedAt": "2026-03-16T10:00:00"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "total": 38,
    "pages": 2
  }
}
```

---

### POST /api/user/shipments

為既有訂單建立新的出貨記錄。訂單必須屬於您的商家。

**認證：** 需要 Bearer token。

**請求 Body：**

| 欄位            | 型別   | 必填 | 說明                                          |
|-----------------|--------|------|-----------------------------------------------|
| `orderId`       | String | 是   | 內部 OMS 訂單 ID                              |
| `trackingNumber`| String | 否   | 物流追蹤號碼                                  |
| `carrier`       | String | 否   | 物流公司（對應 `logisticsCompany`）           |

```bash
curl -s -X POST http://localhost:8082/api/user/shipments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "trackingNumber": "799110012345678",
    "carrier": "BlackCat"
  }'
```

**回應（201 Created）：**
```json
{
  "id": "Sh9pQr4tUvXwYzA2C6Df",
  "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
  "trackingNumber": "799110012345678",
  "logisticsCompany": "BlackCat",
  "shippingStatus": "pending",
  "shippedAt": "2026-03-16T10:00:00",
  "createdAt": "2026-03-16T10:00:00",
  "updatedAt": "2026-03-16T10:00:00"
}
```

**錯誤回應：** `400` 若缺少 `orderId`。`404` 若訂單不存在或屬於不同商家。

---

### GET /api/user/shipments/{id}

依 ID 查詢單一出貨記錄。

**認證：** 需要 Bearer token。

```bash
curl -s "http://localhost:8082/api/user/shipments/Sh9pQr4tUvXwYzA2C6Df" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：** 單一出貨物件（結構與 POST 回應相同）。

**錯誤回應：** `404` 若不存在，或上層訂單屬於不同商家。

---

### PATCH /api/user/shipments/{id}

更新既有出貨記錄的追蹤號碼。

**認證：** 需要 Bearer token。

**請求 Body：**

| 欄位            | 型別   | 必填 | 說明               |
|-----------------|--------|------|--------------------|
| `trackingNumber`| String | 是   | 要設定的新追蹤號碼 |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/shipments/Sh9pQr4tUvXwYzA2C6Df" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"trackingNumber":"799110099999999"}'
```

**回應（200 OK）：** 更新後的出貨物件。

**錯誤回應：** `404` 若不存在或屬於不同商家。

---

### GET /api/user/shipments/by-order/{orderId}

列出特定訂單下的所有出貨記錄。是 `GET /api/user/orders/{id}/shipments` 的替代路徑。

**認證：** 需要 Bearer token。

```bash
curl -s "http://localhost:8082/api/user/shipments/by-order/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：** 出貨物件陣列。

---

## 退款

### GET /api/user/refunds

退款 / 退貨記錄分頁列表。可選擇依 `orderId` 過濾。

**認證：** 需要 Bearer token。

**Query 參數：**

| 參數       | 型別    | 預設值 | 說明                                                    |
|------------|---------|--------|---------------------------------------------------------|
| `page`     | Integer | `1`    | 頁碼（從 1 開始）                                       |
| `pageSize` | Integer | `10`   | 每頁筆數                                                |
| `orderId`  | String  | —      | 篩選特定訂單的退款（回傳不分頁的完整列表）              |

```bash
curl -s "http://localhost:8082/api/user/refunds?page=1&pageSize=10" \
  -H "Authorization: Bearer $TOKEN"

# 依訂單過濾
curl -s "http://localhost:8082/api/user/refunds?orderId=Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）— UserPageResponse：**
```json
{
  "data": [
    {
      "id": "a000002026031610Ab",
      "merchantId": "a00000",
      "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
      "channelReturnId": null,
      "returnStatus": "PENDING",
      "reason": "Product defective",
      "refundAmount": 1250.00,
      "requestedAt": "2026-03-16T14:00:00",
      "approvedAt": null,
      "rejectedAt": null,
      "createdAt": "2026-03-16T14:00:00",
      "updatedAt": "2026-03-16T14:00:00"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 5,
    "pages": 1
  }
}
```

---

### POST /api/user/refunds

建立新的退款 / 退貨申請。

**認證：** 需要 Bearer token。

**請求 Body：**

| 欄位     | 型別   | 必填 | 說明                   |
|----------|--------|------|------------------------|
| `orderId`| String | 否   | 關聯的 OMS 訂單 ID     |
| `reason` | String | 否   | 退貨原因               |
| `amount` | Number | 否   | 退款金額               |

```bash
curl -s -X POST http://localhost:8082/api/user/refunds \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "reason": "Product defective",
    "amount": 1250.00
  }'
```

**回應（201 Created）：** ReturnOrder 物件。ID 格式為 `{merchantPrefix}{yyyyMMddHHmmss}{random2}`。

---

### GET /api/user/refunds/{id}

依 ID 查詢單一退款記錄。

**認證：** 需要 Bearer token。

```bash
curl -s "http://localhost:8082/api/user/refunds/a000002026031610Ab" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：** 單一 ReturnOrder 物件。

**錯誤回應：** `404` 若不存在或屬於不同商家。

---

### PATCH /api/user/refunds/{id}

核准或拒絕退款申請。

**認證：** 需要 Bearer token。

**請求 Body：**

| 欄位     | 型別   | 必填 | 說明                          |
|----------|--------|------|-------------------------------|
| `action` | String | 是   | `"approve"` 或 `"reject"`    |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/refunds/a000002026031610Ab" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"approve"}'
```

**回應（200 OK）：** 更新後的 ReturnOrder 物件，`returnStatus` 設為 `APPROVED` 或 `REJECTED`。

**錯誤回應：** `400` 若 action 不是 `approve` 或 `reject`。`404` 若不存在或屬於不同商家。

---

## 庫存

### GET /api/user/inventory

所有商品及其當前數量與安全庫存量的分頁列表。

**認證：** 需要 Bearer token。

**Query 參數：**

| 參數       | 型別    | 預設值 | 說明               |
|------------|---------|--------|--------------------|
| `page`     | Integer | `1`    | 頁碼（從 1 開始）  |
| `pageSize` | Integer | `20`   | 每頁筆數           |

```bash
curl -s "http://localhost:8082/api/user/inventory?page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）— UserPageResponse：**
```json
{
  "data": [
    {
      "id": "Pr5qRs6tUvWxYzA1B2Cd",
      "merchantId": "a00000",
      "sku": "TSHIRT-BLUE-L",
      "productName": "Premium Cotton T-Shirt (Blue/L)",
      "quantity": 45,
      "safetyQuantity": 10,
      "costPrice": 300.00,
      "createdAt": "2026-01-10T09:00:00",
      "updatedAt": "2026-03-15T08:30:00"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "total": 87,
    "pages": 5
  }
}
```

---

### GET /api/user/inventory/low-stock

只回傳 `quantity <= safetyQuantity` 的商品。

**認證：** 需要 Bearer token。

**Query 參數：** 同 GET /api/user/inventory（`page`、`pageSize`）。

```bash
curl -s "http://localhost:8082/api/user/inventory/low-stock" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：** 格式同 GET /api/user/inventory，過濾為僅低庫存商品。

---

### PATCH /api/user/inventory/{productId}

手動設定（覆寫）商品的庫存絕對數量。此操作為盤點校正，非增量調整。

**認證：** 需要 Bearer token。

**路徑參數：**

| 參數        | 型別   | 說明                              |
|-------------|--------|-----------------------------------|
| `productId` | String | 內部 OMS 商品 ID（NanoID）        |

**請求 Body：**

| 欄位       | 型別    | 必填 | 說明                               |
|------------|---------|------|------------------------------------|
| `quantity` | Integer | 是   | 新的庫存絕對數量（>= 0）           |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/inventory/Pr5qRs6tUvWxYzA1B2Cd" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"quantity":50}'
```

**回應（200 OK）：** 更新後的商品物件。

**錯誤回應：** `400` 若數量為 null 或負數。`404` 若商品不存在或屬於不同商家。

---

## 報表

### GET /api/user/reports/sales

回傳指定日期範圍內的銷售摘要、每日細目與平台細目。

**認證：** 需要 Bearer token。

**Query 參數：**

| 參數        | 型別   | 預設值     | 說明                                              |
|-------------|--------|------------|---------------------------------------------------|
| `from`      | Date   | 7 天前     | 開始日期（ISO-8601：`YYYY-MM-DD`）                |
| `to`        | Date   | 今天       | 結束日期（ISO-8601：`YYYY-MM-DD`，包含當天）      |
| `channelId` | String | —          | 可選：限制特定通路的資料                          |

```bash
curl -s "http://localhost:8082/api/user/reports/sales?from=2026-03-20&to=2026-03-26" \
  -H "Authorization: Bearer $TOKEN"

# 加入通路過濾
curl -s "http://localhost:8082/api/user/reports/sales?from=2026-03-01&to=2026-03-26&channelId=CHANNEL_SHOPEE_001" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
{
  "summary": {
    "totalOrders": 312,
    "totalAmount": 428650.00,
    "totalShipped": 280,
    "totalCompleted": 240,
    "totalCancelled": 18,
    "returnRate": 2.56
  },
  "byDate": [
    {
      "date": "2026-03-20",
      "orderCount": 38,
      "amount": 52400.00
    },
    {
      "date": "2026-03-21",
      "orderCount": 45,
      "amount": 61800.00
    },
    {
      "date": "2026-03-22",
      "orderCount": 0,
      "amount": 0.00
    },
    {
      "date": "2026-03-23",
      "orderCount": 0,
      "amount": 0.00
    },
    {
      "date": "2026-03-24",
      "orderCount": 52,
      "amount": 71250.00
    },
    {
      "date": "2026-03-25",
      "orderCount": 88,
      "amount": 120750.00
    },
    {
      "date": "2026-03-26",
      "orderCount": 89,
      "amount": 122450.00
    }
  ],
  "byPlatform": [
    {
      "platformId": "shopee",
      "orderCount": 180,
      "amount": 247500.00,
      "percentage": 57.69
    },
    {
      "platformId": "momo",
      "orderCount": 95,
      "amount": 130500.00,
      "percentage": 30.45
    },
    {
      "platformId": "cyberbiz",
      "orderCount": 37,
      "amount": 50650.00,
      "percentage": 11.86
    }
  ]
}
```

`returnRate` 以百分比表示（例如 `2.56` 代表 2.56%）。`byDate` 永遠依日期升冪排序，方便繪製圖表。`byPlatform` 依 `orderCount` 降冪排序。

---

### GET /api/user/reports/profit

回傳毛利估算。由於每日統計是在通路 / 日期層級彙總，不包含商品層級的營收細項，因此每個商品的 `revenue` 和 `profit` 回傳為 `null`。此 endpoint 以商品目錄中的 `costPrice` 提供成本參考。

**認證：** 需要 Bearer token。

**Query 參數：** 同銷售報表（`from`、`to`）。

```bash
curl -s "http://localhost:8082/api/user/reports/profit?from=2026-03-01&to=2026-03-26" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
{
  "totalRevenue": 428650.00,
  "totalCost": null,
  "grossProfit": null,
  "grossMargin": null,
  "byProduct": [
    {
      "productId": "Pr5qRs6tUvWxYzA1B2Cd",
      "productName": "Premium Cotton T-Shirt (Blue/L)",
      "revenue": null,
      "cost": 300.00,
      "profit": null
    }
  ]
}
```

---

## 統計

### GET /api/user/stats/daily

回傳指定日期範圍內的原始 `DailyStatistics` 記錄。每筆對應一個通路的一天資料。

**認證：** 需要 Bearer token。

**Query 參數：**

| 參數        | 型別   | 預設值     | 說明                                              |
|-------------|--------|------------|---------------------------------------------------|
| `from`      | Date   | 7 天前     | 開始日期（ISO-8601：`YYYY-MM-DD`）                |
| `to`        | Date   | 今天       | 結束日期（ISO-8601：`YYYY-MM-DD`，包含當天）      |
| `channelId` | String | —          | 可選的通路過濾                                    |

```bash
curl -s "http://localhost:8082/api/user/stats/daily?from=2026-03-20&to=2026-03-26" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
{
  "success": true,
  "data": [
    {
      "id": "stats-row-id",
      "merchantId": "a00000",
      "channelId": "CHANNEL_SHOPEE_001",
      "platformId": "shopee",
      "statDate": "2026-03-26",
      "newOrderCount": 42,
      "newOrderAmount": 57750.00,
      "shippedCount": 38,
      "completedCount": 32,
      "cancelledCount": 3,
      "refundCount": 1,
      "dirtyAt": null,
      "createdAt": "2026-03-26T23:59:59",
      "updatedAt": "2026-03-26T23:59:59"
    }
  ]
}
```

---

### GET /api/user/stats/today

回傳今日跨所有通路的彙總統計資料。

**認證：** 需要 Bearer token。

```bash
curl -s "http://localhost:8082/api/user/stats/today" \
  -H "Authorization: Bearer $TOKEN"
```

**回應（200 OK）：**
```json
{
  "success": true,
  "data": {
    "date": "2026-03-28",
    "totalOrders": 89,
    "totalAmount": 122450.00,
    "channels": [
      {
        "channelId": "CHANNEL_SHOPEE_001",
        "platformId": "shopee",
        "newOrderCount": 55,
        "newOrderAmount": 75625.00
      },
      {
        "channelId": "CHANNEL_MOMO_001",
        "platformId": "momo",
        "newOrderCount": 34,
        "newOrderAmount": 46825.00
      }
    ]
  }
}
```

---

## Enum

### GET /api/enums/order-statuses

回傳所有有效的訂單狀態碼、標籤與說明。此 endpoint 為公開存取，不需認證。可用來填充 UI 中的狀態過濾下拉選單。

**認證：** 不需要。

```bash
curl -s http://localhost:8082/api/enums/order-statuses
```

**回應（200 OK）：**
```json
{
  "data": [
    {
      "code": "pending",
      "label": "待付款",
      "description": "Order placed but payment not yet confirmed"
    },
    {
      "code": "confirmed",
      "label": "已確認",
      "description": "Payment confirmed, awaiting fulfillment"
    },
    {
      "code": "ready_to_ship",
      "label": "備貨中",
      "description": "Items picked and packed, ready for carrier pickup"
    },
    {
      "code": "shipping",
      "label": "配送中",
      "description": "Handed to carrier, in transit"
    },
    {
      "code": "shipped",
      "label": "已出貨",
      "description": "Shipment confirmed on platform"
    },
    {
      "code": "completed",
      "label": "已完成",
      "description": "Buyer confirmed receipt"
    },
    {
      "code": "cancelled",
      "label": "已取消",
      "description": "Order cancelled"
    }
  ]
}
```

---

## 健康檢查

### GET /api/health

基本的存活檢查。不需認證。

```bash
curl -s http://localhost:8082/api/health
```

**回應（200 OK）：**
```json
{
  "status": "UP",
  "service": "SimpleEC OMS API",
  "timestamp": 1743120000000
}
```

---

### GET /api/version

回傳已部署的服務版本資訊。不需認證。

```bash
curl -s http://localhost:8082/api/version
```

**回應（200 OK）：**
```json
{
  "version": "1.0.0",
  "build": 1743120000000
}
```
