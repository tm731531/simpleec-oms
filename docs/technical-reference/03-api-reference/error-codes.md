# 錯誤碼與回應格式

本文件說明 SimpleEC OMS 使用的 HTTP 狀態碼、兩種回應封裝格式，以及常見錯誤情境與範例回應。

---

## 回應封裝格式

根據呼叫的 API 系列，回應有兩種封裝形式。

### User API 回應

用戶端 endpoint（`/api/auth/**`、`/api/user/**`、`/api/enums/**`、`/api/health`）直接回傳 JSON 物件，列表類則回傳 `UserPageResponse`：

**單一物件（直接回傳）：**
```json
{
  "id": "Kp7mNqR3xVoWyZj2B5Lc",
  "merchantId": "a00000",
  "orderStatus": "pending",
  ...
}
```

**分頁列表（UserPageResponse）：**
```json
{
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 142,
    "pages": 15
  }
}
```

**錯誤（直接回傳）：**
```json
{
  "error": "帳號或密碼錯誤"
}
```

### Admin API 回應

管理員 endpoint（`/api/admin/**`）始終使用 `AdminApiResponse<T>` 封裝格式：

**成功並含資料：**
```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

**成功並含分頁資料：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 45,
    "page": 1,
    "pageSize": 20,
    "totalPages": 3,
    "items": [ ... ]
  }
}
```

**成功但無資料（例如 DELETE）：**
```json
{
  "code": 200,
  "message": "success"
}
```

**錯誤：**
```json
{
  "code": 404,
  "message": "Account not found: V4nKq8mR2xLpYoZw1A3B"
}
```

---

## HTTP 狀態碼

| 狀態碼 | 名稱                  | 使用時機                                                                                   |
|--------|-----------------------|-------------------------------------------------------------------------------------------|
| `200`  | OK                    | 成功的 GET、PATCH、PUT、DELETE。管理員 POST 成功時也回傳此碼（搭配 envelope 中的 `code: 200`）。 |
| `201`  | Created               | 成功的 POST 建立了新資源（例如 POST /api/user/shipments）。                               |
| `202`  | Accepted              | 訊息已接受並進行非同步處理（POST /api/user/orders 發布至 Kafka）。                        |
| `400`  | Bad Request           | 缺少必填欄位、驗證失敗（email 格式、密碼長度等）。                                        |
| `401`  | Unauthorized          | 缺少 Authorization header；JWT 無效或已過期；登入憑證錯誤。                               |
| `403`  | Forbidden             | token 有效但角色不足（例如商家帳號呼叫管理員 endpoint）；帳號已停用。                     |
| `404`  | Not Found             | 資源不存在，或資源存在但屬於不同商家。                                                    |
| `409`  | Conflict              | 唯一值重複（email 已註冊、平台名稱已存在）。                                              |
| `500`  | Internal Server Error | 伺服器端發生非預期錯誤。                                                                  |

---

## 400 Bad Request

當請求在到達資料庫前即存在語法或語意錯誤時回傳。

### 認證時缺少必填欄位
```
HTTP/1.1 400 Bad Request

{
  "error": "email 和 password 為必填"
}
```

### 提交訂單時缺少必填欄位
```
HTTP/1.1 400 Bad Request

{
  "error": "channelId and channelOrderId are required"
}
```

### 找不到商家的通路
```
HTTP/1.1 400 Bad Request

{
  "error": "Channel not found: CHANNEL_UNKNOWN_001"
}
```

### email 格式無效（建立管理員帳號）
```
HTTP/1.1 400 Bad Request

{
  "code": 400,
  "message": "Invalid email format: not-an-email"
}
```

### 密碼過短（建立管理員帳號）
```
HTTP/1.1 400 Bad Request

{
  "code": 400,
  "message": "Password must be at least 8 characters"
}
```

### 建立帳號時找不到商家
```
HTTP/1.1 400 Bad Request

{
  "code": 400,
  "message": "Merchant not found: xxxxxx"
}
```

### 訂單 PATCH 的 action 無效
```
HTTP/1.1 400 Bad Request
（空 body）
```

### 庫存 PATCH 的數量無效
```
HTTP/1.1 400 Bad Request
（空 body）
```

---

## 401 Unauthorized

當認證缺失或無效時回傳。

### 未提供 token（Spring Security 預設行為）
```
HTTP/1.1 401 Unauthorized
（空 body 或 Spring 預設錯誤頁面）
```

### 登入時憑證錯誤
```
HTTP/1.1 401 Unauthorized

{
  "error": "帳號或密碼錯誤"
}
```

### Token 過期或無效（JwtAuthFilter）
```
HTTP/1.1 401 Unauthorized
（空 body — JwtAuthFilter 設定狀態碼 401 後直接返回）
```

### 呼叫 /api/auth/me 時未認證
```
HTTP/1.1 401 Unauthorized

{
  "error": "未授權"
}
```

---

## 403 Forbidden

當用戶已認證但缺乏所需角色，或帳號已停用時回傳。

### 登入時帳號已停用
```
HTTP/1.1 403 Forbidden

{
  "error": "帳號已停用"
}
```

### 商家帳號呼叫管理員 endpoint
```
HTTP/1.1 403 Forbidden
（空 body — Spring Security hasAuthority 驗證失敗）
```

### 存取屬於不同商家的訂單（為防止資料存在性洩露，回傳 404）
```
HTTP/1.1 404 Not Found
（空 body）
```

### 通路不屬於您的商家（提交訂單）
```
HTTP/1.1 403 Forbidden

{
  "error": "Channel does not belong to your merchant"
}
```

---

## 404 Not Found

當資源不存在，或資源存在但屬於不同商家時回傳。系統回傳 404 而非 403，以避免洩露其他商家資料是否存在。

### 訂單不存在（User API）
```
HTTP/1.1 404 Not Found
（空 body）
```

### 帳號不存在（Admin API）
```
HTTP/1.1 404 Not Found

{
  "code": 404,
  "message": "Account not found: V4nKq8mR2xLpYoZw1A3B"
}
```

### 商家不存在（Admin API）
```
HTTP/1.1 404 Not Found

{
  "code": 404,
  "message": "Merchant not found: xxxxxx"
}
```

### 平台不存在（Admin API）
```
HTTP/1.1 404 Not Found

{
  "code": 404,
  "message": "Platform not found: PLT_UNKNOWN"
}
```

---

## 409 Conflict

當唯一性約束將被違反時回傳。

### 重複的 email（建立帳號）
```
HTTP/1.1 409 Conflict

{
  "code": 409,
  "message": "Account email already exists: staff@acme-shop.com"
}
```

### 重複的商家 email
```
HTTP/1.1 409 Conflict

{
  "code": 409,
  "message": "Merchant email already exists: contact@acme-shop.com"
}
```

### 重複的平台名稱
```
HTTP/1.1 409 Conflict

{
  "code": 409,
  "message": "Platform name already exists: shopee"
}
```

---

## 500 Internal Server Error

當管理員 endpoint 發生非預期例外時回傳。User endpoint 通常不以此方式包裝錯誤。

```
HTTP/1.1 500 Internal Server Error

{
  "code": 500,
  "message": "Failed to list accounts: Connection refused"
}
```

---

## 分頁參考

User API 分頁回應使用 `UserPageResponse`：

```json
{
  "data": [
    { "id": "...", ... },
    { "id": "...", ... }
  ],
  "pagination": {
    "page": 2,
    "pageSize": 10,
    "total": 142,
    "pages": 15
  }
}
```

| 欄位       | 型別    | 說明                                    |
|------------|---------|-----------------------------------------|
| `data`     | Array   | 當前頁的資料項目                        |
| `page`     | Integer | 當前頁碼（從 1 開始）                   |
| `pageSize` | Integer | 每頁請求的項目數                        |
| `total`    | Long    | 資料庫中符合條件的總筆數                |
| `pages`    | Integer | 總頁數（`ceil(total/pageSize)`）        |

Admin API 分頁回應使用 `PageResponse`：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 45,
    "page": 1,
    "pageSize": 20,
    "totalPages": 3,
    "items": [ ... ]
  }
}
```

| 欄位         | 型別    | 說明                                    |
|--------------|---------|-----------------------------------------|
| `items`      | Array   | 當前頁的資料項目                        |
| `page`       | Integer | 當前頁碼（從 1 開始）                   |
| `pageSize`   | Integer | 每頁請求的項目數                        |
| `total`      | Long    | 符合條件的總筆數                        |
| `totalPages` | Integer | 總頁數                                  |

**分頁預設值與上限：**

| Endpoint 群組              | 預設頁碼 | 預設每頁筆數 | 最大每頁筆數    |
|----------------------------|----------|--------------|-----------------|
| `GET /api/user/orders`     | 1        | 10           | 100             |
| `GET /api/user/shipments`  | 1        | 10           | （無強制上限）  |
| `GET /api/user/refunds`    | 1        | 10           | （無強制上限）  |
| `GET /api/user/inventory`  | 1        | 20           | （無強制上限）  |
| `GET /api/admin/account`   | 1        | 20           | 100             |
| `GET /api/admin/merchant`  | 1        | 20           | 100             |
| `GET /api/admin/platform`  | 1        | 20           | 100             |

---

## 常見錯誤情境

### 情境 1：未帶 token 呼叫受保護的 endpoint

```bash
curl -s http://localhost:8082/api/user/orders
# → 401 Unauthorized（空 body）
```

### 情境 2：Token 過期

Token 有效期為 7 天。過期後，JwtAuthFilter 會拒絕該 token 並回傳 401。

```bash
curl -s http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer <expired-token>"
# → 401 Unauthorized（空 body）
```

解決方式：重新呼叫 `POST /api/auth/login` 取得新 token。

### 情境 3：商家用戶嘗試存取管理員 endpoint

```bash
curl -s http://localhost:8082/api/admin/account \
  -H "Authorization: Bearer <merchant-token>"
# → 403 Forbidden（空 body）
```

### 情境 4：存取屬於不同商家的訂單

```bash
# 訂單 Kp7m... 屬於商家 a00000
# 目前以商家 a00001 認證
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer <merchant-a00001-token>"
# → 404 Not Found（空 body）
# 回傳 404 是刻意設計 — 避免確認該資源是否存在
```

### 情境 5：提交含無效狀態的訂單

OMS 是被動同步系統，不強制狀態轉換。任何能被 `OrderStatusEnum.fromCode()` 接受的狀態碼都有效。未知的狀態碼會被靜默處理（訂單不會被建立，依該狀態過濾時列表 endpoint 回傳空頁）。

### 情境 6：以已存在的 email 建立帳號

```bash
curl -s -X POST http://localhost:8082/api/admin/account \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"merchantId":"a00000","accountEmail":"owner@acme-shop.com","accountPassword":"pass1234"}'
# → 409 Conflict
# {"code":409,"message":"Account email already exists: owner@acme-shop.com"}
```

### 情境 7：非同步提交訂單（202）

`POST /api/user/orders` 立即回傳 `202 Accepted`。此時訂單尚未寫入資料庫 — 訊息已加入 Kafka 佇列中。請稍待片刻後查詢 `GET /api/user/orders` 確認是否已完成持久化。

```bash
curl -s -X POST http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"channelId":"CHANNEL_SHOPEE_001","channelOrderId":"250328001","totalAmount":500}'
# → 202 Accepted
# {"channelOrderId":"250328001","status":"accepted"}
```
