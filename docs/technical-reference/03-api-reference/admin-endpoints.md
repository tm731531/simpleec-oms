# Admin API Endpoint

平台管理員 endpoint，用於管理商家、帳號與平台設定。所有管理員 endpoint 都需要使用 `/api/admin/auth/login` 簽發的 JWT，且角色必須為 `platform_admin`。

**Base URL：** `http://localhost:8082`

**認證 header：**
```
Authorization: Bearer <admin-token>
```

Spring Security 對此命名空間下的每個請求強制執行 `.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")`。使用商家角色 token（`main`/`sub`）將收到 403 Forbidden。

---

## 回應格式

所有管理員 endpoint 回傳 `AdminApiResponse<T>` 封裝格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

發生錯誤時，`data` 為 `null`，`message` 包含錯誤說明。`code` 欄位與 HTTP 狀態碼一致。

分頁回應時，`data` 為 `PageResponse<T>`：

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

---

## 目錄

- [管理員認證](#管理員認證)
  - [POST /api/admin/auth/login](#post-apiadminauthlogin)
- [帳號管理](#帳號管理)
  - [GET /api/admin/account](#get-apiadminaccount)
  - [GET /api/admin/account/{id}](#get-apiadminaccountid)
  - [POST /api/admin/account](#post-apiadminaccount)
  - [PUT /api/admin/account/{id}](#put-apiadminaccountid)
  - [DELETE /api/admin/account/{id}](#delete-apiadminaccountid)
  - [POST /api/admin/account/{id}/reset-password](#post-apiadminaccountidresetpassword)
- [商家管理](#商家管理)
  - [GET /api/admin/merchant](#get-apiadminmerchant)
  - [GET /api/admin/merchant/{id}](#get-apiadminmerchantid)
  - [POST /api/admin/merchant](#post-apiadminmerchant)
  - [PUT /api/admin/merchant/{id}](#put-apiadminmerchantid)
  - [DELETE /api/admin/merchant/{id}](#delete-apiadminmerchantid)
- [平台管理](#平台管理)
  - [GET /api/admin/platform](#get-apiadminplatform)
  - [GET /api/admin/platform/{id}](#get-apiadminplatformid)
  - [POST /api/admin/platform](#post-apiadminplatform)
  - [PUT /api/admin/platform/{id}](#put-apiadminplatformid)
  - [DELETE /api/admin/platform/{id}](#delete-apiadminplatformid)

---

## 管理員認證

### POST /api/admin/auth/login

以平台管理員身份進行認證。回傳角色為 `platform_admin` 的 JWT。

**認證：** 不需要。

**請求 Body：**

| 欄位       | 型別   | 必填 | 說明                     |
|------------|--------|------|--------------------------|
| `email`    | String | 是   | 平台管理員帳號 email     |
| `password` | String | 是   | 平台管理員密碼           |

```bash
curl -s -X POST http://localhost:8082/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@simpleec.com",
    "password": "AdminSecret!"
  }'
```

**回應（200 OK）：**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJQcVI5c1QydVY1d1g4eVowYUIxQyIsIm1lcmNoYW50SWQiOiIiLCJlbWFpbCI6ImFkbWluQHNpbXBsZWVjLmNvbSIsIm5hbWUiOiJQbGF0Zm9ybSBBZG1pbiIsInJvbGUiOiJwbGF0Zm9ybV9hZG1pbiIsImlhdCI6MTc0MzEyMDAwMCwiZXhwIjoxNzQzNzI0ODAwfQ.SIG",
  "user": {
    "id": "PqR9sT2uV5wX8yZ0aB1C",
    "email": "admin@simpleec.com",
    "name": "Platform Admin",
    "role": "platform_admin"
  }
}
```

注意：管理員 token 的 `merchantId: ""`。後續所有管理員 API 呼叫可跨商家操作。

**錯誤回應：** `400` 缺少欄位，`401` 憑證錯誤，`403` 帳號已停用。

---

## 帳號管理

帳號為商家的用戶帳號（非平台管理員帳號）。每個帳號透過 `merchantId` 隸屬於一個商家。

### GET /api/admin/account

分頁列出所有帳號。可依 `merchantId` 過濾，或進行關鍵字搜尋。

**認證：** 需要平台管理員 Bearer token。

**Query 參數：**

| 參數         | 型別    | 預設值       | 說明                                                             |
|--------------|---------|--------------|------------------------------------------------------------------|
| `page`       | Integer | `1`          | 頁碼（從 1 開始）。最小值：1。                                   |
| `pageSize`   | Integer | `20`         | 每頁筆數。最小值：1。最大值：100。                               |
| `merchantId` | String  | —            | 篩選特定商家下的帳號                                             |
| `search`     | String  | —            | 以帳號名稱或 email 搜尋（若同時指定 merchantId，則在其範圍內搜尋） |
| `sortBy`     | String  | `createdAt`  | 排序欄位（接受但目前忽略，使用預設排序）                         |
| `order`      | String  | `desc`       | 排序方向：`asc` 或 `desc`                                        |

```bash
ADMIN_TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# 列出所有帳號
curl -s "http://localhost:8082/api/admin/account?page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 依商家篩選
curl -s "http://localhost:8082/api/admin/account?merchantId=a00000&page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 在商家範圍內搜尋
curl -s "http://localhost:8082/api/admin/account?merchantId=a00000&search=alice&page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 12,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1,
    "items": [
      {
        "id": "V4nKq8mR2xLpYoZw1A3B",
        "merchantId": "a00000",
        "accountEmail": "owner@acme-shop.com",
        "accountName": "Alice Chen",
        "accountTel": "0912345678",
        "isMainAccount": true,
        "accessLevel": null,
        "status": "enable",
        "accountPassword": null,
        "createdAt": "2026-01-10T09:00:00",
        "updatedAt": "2026-01-10T09:00:00"
      }
    ]
  }
}
```

注意：`accountPassword` 永遠回傳為 `null`，密碼絕不透過 API 公開。

---

### GET /api/admin/account/{id}

依 ID 查詢單一帳號。

**認證：** 需要平台管理員 Bearer token。

```bash
curl -s "http://localhost:8082/api/admin/account/V4nKq8mR2xLpYoZw1A3B" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "V4nKq8mR2xLpYoZw1A3B",
    "merchantId": "a00000",
    "accountEmail": "owner@acme-shop.com",
    "accountName": "Alice Chen",
    "accountTel": "0912345678",
    "isMainAccount": true,
    "accessLevel": null,
    "status": "enable",
    "accountPassword": null,
    "createdAt": "2026-01-10T09:00:00",
    "updatedAt": "2026-01-10T09:00:00"
  }
}
```

**錯誤回應：** `404` 帳號不存在，`500` 伺服器錯誤。

---

### POST /api/admin/account

建立新的商家帳號。

**認證：** 需要平台管理員 Bearer token。

**請求 Body：**

| 欄位              | 型別    | 必填 | 驗證規則                                |
|-------------------|---------|------|-----------------------------------------|
| `merchantId`      | String  | 是   | 必須對應既有的商家                      |
| `accountEmail`    | String  | 是   | 有效的 email 格式；必須唯一             |
| `accountPassword` | String  | 是   | 最少 8 個字元                           |
| `accountName`     | String  | 否   | 顯示名稱                                |
| `accountTel`      | String  | 否   | 電話號碼                                |
| `isMainAccount`   | Boolean | 否   | `true` = 主帳號（角色：`main`）         |
| `accessLevel`     | String  | 否   | 子帳號的自訂存取等級                    |
| `status`          | String  | 否   | `"enable"`（預設）或 `"disable"`        |

```bash
curl -s -X POST http://localhost:8082/api/admin/account \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "a00000",
    "accountEmail": "staff@acme-shop.com",
    "accountPassword": "StaffPass99",
    "accountName": "Bob Wang",
    "accountTel": "0987654321",
    "isMainAccount": false,
    "status": "enable"
  }'
```

**回應（201 Created）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "Nm8oOp9qRs0tUv1wXy2Z",
    "merchantId": "a00000",
    "accountEmail": "staff@acme-shop.com",
    "accountName": "Bob Wang",
    "accountTel": "0987654321",
    "isMainAccount": false,
    "accessLevel": null,
    "status": "enable",
    "accountPassword": null,
    "createdAt": "2026-03-28T10:00:00",
    "updatedAt": "2026-03-28T10:00:00"
  }
}
```

**錯誤回應：**
- `400` 缺少必填欄位、email 格式無效、密碼過短（< 8 字元）、商家不存在
- `409` email 已存在
- `500` 伺服器錯誤

---

### PUT /api/admin/account/{id}

更新既有帳號。只需提供要修改的欄位，未提供的欄位保持原值不變。

**認證：** 需要平台管理員 Bearer token。

**可更新欄位：**

| 欄位            | 型別    | 說明                                       |
|-----------------|---------|--------------------------------------------|
| `accountName`   | String  | 顯示名稱                                   |
| `accountEmail`  | String  | 新 email（驗證格式，並檢查唯一性）         |
| `accountTel`    | String  | 電話號碼                                   |
| `isMainAccount` | Boolean | 升級／降級主帳號旗標                       |
| `accessLevel`   | String  | 自訂存取等級                               |
| `status`        | String  | `"enable"` 或 `"disable"`                 |

注意：密碼變更必須使用專屬的 `POST /api/admin/account/{id}/reset-password` endpoint。

```bash
curl -s -X PUT "http://localhost:8082/api/admin/account/Nm8oOp9qRs0tUv1wXy2Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accountName": "Bob Wang (Manager)",
    "status": "enable"
  }'
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "Nm8oOp9qRs0tUv1wXy2Z",
    "merchantId": "a00000",
    "accountEmail": "staff@acme-shop.com",
    "accountName": "Bob Wang (Manager)",
    "status": "enable",
    "accountPassword": null
  }
}
```

**錯誤回應：** `400` email 無效，`404` 帳號不存在，`409` email 衝突，`500` 伺服器錯誤。

---

### DELETE /api/admin/account/{id}

永久刪除帳號。

**認證：** 需要平台管理員 Bearer token。

```bash
curl -s -X DELETE "http://localhost:8082/api/admin/account/Nm8oOp9qRs0tUv1wXy2Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success"
}
```

**錯誤回應：** `404` 帳號不存在，`500` 伺服器錯誤。

---

### POST /api/admin/account/{id}/reset-password

重設特定帳號的密碼。

**認證：** 需要平台管理員 Bearer token。

**請求 Body：**

| 欄位          | 型別   | 必填 | 驗證規則             |
|---------------|--------|------|----------------------|
| `newPassword` | String | 是   | 最少 8 個字元        |

```bash
curl -s -X POST "http://localhost:8082/api/admin/account/V4nKq8mR2xLpYoZw1A3B/reset-password" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"newPassword":"NewSecurePass42"}'
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success"
}
```

**錯誤回應：** `400` 密碼缺失或過短，`404` 帳號不存在，`500` 伺服器錯誤。

---

## 商家管理

商家是最上層的租戶（tenant）。每個商家擁有獨立的帳號、通路、訂單與統計資料。

### GET /api/admin/merchant

分頁列出商家，支援關鍵字搜尋。

**認證：** 需要平台管理員 Bearer token。

**Query 參數：**

| 參數       | 型別    | 預設值 | 說明                                |
|------------|---------|--------|-------------------------------------|
| `page`     | Integer | `1`    | 頁碼（從 1 開始）                   |
| `pageSize` | Integer | `20`   | 每頁筆數。最大值：100。             |
| `search`   | String  | —      | 以商家名稱或 email 搜尋             |

```bash
curl -s "http://localhost:8082/api/admin/merchant?page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 搜尋
curl -s "http://localhost:8082/api/admin/merchant?search=acme" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 3,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1,
    "items": [
      {
        "id": "a00000",
        "merchantName": "ACME Shop",
        "merchantEmail": "contact@acme-shop.com",
        "merchantPhoneNumber": "02-12345678",
        "taxIdNumber": "12345678",
        "addressCity": "Taipei",
        "addressRegion": "Zhongshan District",
        "addressCountry": "TW",
        "addressZip": "104",
        "addressLine1": "No. 1, Section 1, Zhongshan N Rd",
        "addressLine2": null,
        "vipLevel": 1,
        "userLocalTimeZone": "Asia/Taipei",
        "status": "enable",
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00"
      }
    ]
  }
}
```

---

### GET /api/admin/merchant/{id}

依 ID 查詢單一商家。

**認證：** 需要平台管理員 Bearer token。

```bash
curl -s "http://localhost:8082/api/admin/merchant/a00000" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：** 商家物件，以 AdminApiResponse 封裝。欄位同列表項目。

**錯誤回應：** `404` 不存在，`500` 伺服器錯誤。

---

### POST /api/admin/merchant

建立新商家（租戶）。ID 自動產生為 6 字元 NanoID 前綴。

**認證：** 需要平台管理員 Bearer token。

**請求 Body：**

| 欄位                  | 型別    | 必填 | 驗證規則                                      |
|-----------------------|---------|------|-----------------------------------------------|
| `merchantName`        | String  | 是   | 商家顯示名稱                                  |
| `merchantEmail`       | String  | 是   | 有效的 email 格式；全系統必須唯一             |
| `merchantPhoneNumber` | String  | 否   | 公司電話                                      |
| `taxIdNumber`         | String  | 否   | 統一編號 / 公司登記號碼                       |
| `addressCity`         | String  | 否   | 城市                                          |
| `addressRegion`       | String  | 否   | 區域                                          |
| `addressCountry`      | String  | 否   | ISO 3166-1 alpha-2 國家代碼                   |
| `addressZip`          | String  | 否   | 郵遞區號                                      |
| `addressLine1`        | String  | 否   | 地址第一行                                    |
| `addressLine2`        | String  | 否   | 地址第二行                                    |
| `vipLevel`            | Integer | 否   | VIP 等級                                      |
| `userLocalTimeZone`   | String  | 否   | IANA 時區（例如 `"Asia/Taipei"`）             |
| `status`              | String  | 否   | `"enable"`（預設）或 `"disable"`              |

```bash
curl -s -X POST http://localhost:8082/api/admin/merchant \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantName": "New Store Ltd",
    "merchantEmail": "hello@newstore.com",
    "merchantPhoneNumber": "03-9876543",
    "taxIdNumber": "87654321",
    "addressCountry": "TW",
    "userLocalTimeZone": "Asia/Taipei",
    "status": "enable"
  }'
```

**回應（201 Created）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "b1c2d3",
    "merchantName": "New Store Ltd",
    "merchantEmail": "hello@newstore.com",
    "status": "enable",
    "createdAt": "2026-03-28T10:00:00",
    "updatedAt": "2026-03-28T10:00:00"
  }
}
```

**錯誤回應：** `400` 缺少名稱或 email、email 格式無效。`409` email 已存在。`500` 伺服器錯誤。

---

### PUT /api/admin/merchant/{id}

更新商家資訊。只需提供要修改的欄位。

**認證：** 需要平台管理員 Bearer token。

**可更新欄位：** `merchantName`、`merchantEmail`（驗證格式，並檢查唯一性）、`merchantPhoneNumber`、`taxIdNumber`、`addressCity`、`addressRegion`、`addressCountry`、`addressZip`、`addressLine1`、`addressLine2`、`addressPhoneNumber`、`vipLevel`、`userLocalTimeZone`、`payerName`、`payerEmail`、`payerPhoneNumber`、`status`。

```bash
curl -s -X PUT "http://localhost:8082/api/admin/merchant/b1c2d3" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantName": "New Store Ltd (Upgraded)",
    "vipLevel": 2,
    "status": "enable"
  }'
```

**回應（200 OK）：** 更新後的商家物件，以 AdminApiResponse 封裝。

**錯誤回應：** `400` email 無效，`404` 不存在，`409` email 衝突，`500` 伺服器錯誤。

---

### DELETE /api/admin/merchant/{id}

永久刪除商家。

**認證：** 需要平台管理員 Bearer token。

```bash
curl -s -X DELETE "http://localhost:8082/api/admin/merchant/b1c2d3" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success"
}
```

**錯誤回應：** `404` 不存在，`500` 伺服器錯誤。

---

## 平台管理

平台代表系統整合的電商通路（Shopee、Momo、Yahoo、PChome、Cyberbiz、Easystore 等）。每個平台有對應的 Kafka topic 映射，並可選擇性地儲存 API 憑證。

### GET /api/admin/platform

分頁列出平台。支援關鍵字搜尋及啟用狀態篩選。

**認證：** 需要平台管理員 Bearer token。

**Query 參數：**

| 參數       | 型別    | 預設值 | 說明                                        |
|------------|---------|--------|---------------------------------------------|
| `page`     | Integer | `1`    | 頁碼（從 1 開始）                           |
| `pageSize` | Integer | `20`   | 每頁筆數。最大值：100。                     |
| `search`   | String  | —      | 以平台名稱或 Kafka queue topic 搜尋         |
| `actived`  | Boolean | —      | 依啟用狀態篩選（`true` 或 `false`）         |

```bash
curl -s "http://localhost:8082/api/admin/platform?page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 僅查詢啟用的平台
curl -s "http://localhost:8082/api/admin/platform?actived=true" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 6,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1,
    "items": [
      {
        "id": "PLT_SHOPEE_001",
        "platformName": "shopee",
        "queueTopic": "shopee",
        "currency": "TWD",
        "actived": true,
        "shipOptions": null,
        "credential1": null,
        "credential2": null,
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00"
      },
      {
        "id": "PLT_MOMO_001",
        "platformName": "momo",
        "queueTopic": "momo",
        "currency": "TWD",
        "actived": true,
        "shipOptions": null,
        "credential1": null,
        "credential2": null,
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00"
      }
    ]
  }
}
```

注意：`credential1` 與 `credential2` 儲存平台的 API key / secret。這些欄位在管理員 API 中可見，應視為敏感資料謹慎處理。

---

### GET /api/admin/platform/{id}

依 ID 查詢單一平台。

**認證：** 需要平台管理員 Bearer token。

```bash
curl -s "http://localhost:8082/api/admin/platform/PLT_SHOPEE_001" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：** 平台物件，以 AdminApiResponse 封裝。

**錯誤回應：** `404` 不存在，`500` 伺服器錯誤。

---

### POST /api/admin/platform

建立新平台設定。

**認證：** 需要平台管理員 Bearer token。

**請求 Body：**

| 欄位           | 型別    | 必填 | 驗證規則                                        |
|----------------|---------|------|-------------------------------------------------|
| `platformName` | String  | 是   | 必須唯一（區分大小寫）                          |
| `queueTopic`   | String  | 否   | Kafka topic 前綴（預設值由程式碼處理）          |
| `currency`     | String  | 否   | ISO 4217 貨幣代碼。預設：`"TWD"`               |
| `actived`      | Boolean | 否   | 預設：`true`                                    |
| `credential1`  | String  | 否   | 平台 API key 或 app ID                          |
| `credential2`  | String  | 否   | 平台 API secret 或 token                        |
| `shipOptions`  | Object  | 否   | 平台特定的出貨選項 JSON 物件                    |

```bash
curl -s -X POST http://localhost:8082/api/admin/platform \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "platformName": "easystore",
    "queueTopic": "easystore",
    "currency": "TWD",
    "actived": true
  }'
```

**回應（201 Created）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "Fg7hIj8kLm9nOp0qRs1T",
    "platformName": "easystore",
    "queueTopic": "easystore",
    "currency": "TWD",
    "actived": true,
    "createdAt": "2026-03-28T10:00:00",
    "updatedAt": "2026-03-28T10:00:00"
  }
}
```

**錯誤回應：** `400` 缺少平台名稱。`409` 平台名稱已存在。`500` 伺服器錯誤。

---

### PUT /api/admin/platform/{id}

更新平台設定。

**認證：** 需要平台管理員 Bearer token。

**可更新欄位：** `platformName`（驗證唯一性）、`queueTopic`、`currency`、`actived`、`credential1`、`credential2`、`shipOptions`。

```bash
curl -s -X PUT "http://localhost:8082/api/admin/platform/Fg7hIj8kLm9nOp0qRs1T" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "actived": false
  }'
```

**回應（200 OK）：** 更新後的平台物件，以 AdminApiResponse 封裝。

**錯誤回應：** `404` 不存在，`409` 名稱衝突，`500` 伺服器錯誤。

---

### DELETE /api/admin/platform/{id}

永久刪除平台設定。

**認證：** 需要平台管理員 Bearer token。

```bash
curl -s -X DELETE "http://localhost:8082/api/admin/platform/Fg7hIj8kLm9nOp0qRs1T" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**回應（200 OK）：**
```json
{
  "code": 200,
  "message": "success"
}
```

**錯誤回應：** `404` 不存在，`500` 伺服器錯誤。
