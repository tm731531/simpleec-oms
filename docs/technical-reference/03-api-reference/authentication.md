# 認證與授權

SimpleEC OMS 採用無狀態的 JWT（JSON Web Token）認證機制。伺服器端不保存任何 session。每一個受保護的請求都必須在 `Authorization` header 中攜帶有效的 Bearer token。

---

## 運作原理

```
1. 用戶端將憑證（email + password）傳送到 /api/auth/login
2. 伺服器驗證 accounts 資料表中的憑證
3. 伺服器簽發包含身份資訊的 JWT 並回傳
4. 用戶端儲存 token（localStorage / memory）
5. 用戶端在後續每個請求中附上 token：
       Authorization: Bearer <token>
6. JwtAuthFilter 攔截每個請求，驗證簽章，
   並將 UserPrincipal 注入 Spring Security context
7. Controller 透過 @AuthenticationPrincipal UserPrincipal 取得 principal
```

目前沒有 refresh token 機制。Token 過期後，用戶端必須重新登入。

---

## Token Payload（Claims）

JWT 使用 HMAC-SHA 簽章（金鑰長度 >= 256 bits）。Payload 包含以下欄位：

| Claim        | 型別   | 說明                                             |
|--------------|--------|--------------------------------------------------|
| `sub`        | String | 帳號 ID（NanoID，20 字元）                       |
| `merchantId` | String | 帳號所屬的商家（6 字元 NanoID）                  |
| `email`      | String | 帳號 email 地址                                  |
| `name`       | String | 帳號顯示名稱                                     |
| `role`       | String | `main`、`sub` 或 `platform_admin`                |
| `iat`        | Number | 簽發時間戳（Unix 秒）                            |
| `exp`        | Number | 到期時間戳（Unix 秒）                            |

### 解碼後的 Payload 範例

```json
{
  "sub": "V4nKq8mR2xLpYoZw1A3B",
  "merchantId": "a00000",
  "email": "owner@acme-shop.com",
  "name": "Alice Chen",
  "role": "main",
  "iat": 1743120000,
  "exp": 1743724800
}
```

### Token 有效期

Token 自簽發起，有效期為 **7 天（604800 秒）**。有效期可透過 `application.yml` 的 `jwt.expiration` 進行設定。

---

## 角色

| 角色             | 說明                                                                 |
|------------------|----------------------------------------------------------------------|
| `main`           | 商家的主帳號。可完整存取該商家的所有資源。                           |
| `sub`            | 商家的子帳號。同一商家範疇；UI 功能通常受限。                        |
| `platform_admin` | 平台級管理員。可管理商家、帳號與平台設定。                           |

### Spring Security 授權

| 角色             | GrantedAuthority                      |
|------------------|---------------------------------------|
| `main`           | `ROLE_MERCHANT_MAIN`、`ROLE_USER`     |
| `sub`            | `ROLE_USER`                           |
| `platform_admin` | `ROLE_PLATFORM_ADMIN`、`ROLE_USER`    |

所有 `/api/admin/**` endpoint 都需要 `ROLE_PLATFORM_ADMIN`。一般用戶 endpoint 只需任何已認證的 principal 即可存取。

---

## 商家資料隔離

每個回傳商家資料的 controller，都只從 JWT principal 中讀取 `merchantId`，絕不從 request body 或 query 參數取得。此行為由 `@AuthenticationPrincipal UserPrincipal principal` 強制執行。

```java
// 範例：UserOrderController.listOrders
orders = orderRepository.findByMerchantId(principal.getMerchantId(), pageable);
```

商家 `a00001` 的用戶即使猜到了 ID，也永遠無法看到商家 `a00002` 的資料，因為查詢語句始終以 JWT 中的 `merchantId` 過濾。如果某筆資源（例如訂單）存在但屬於不同商家，API 會回傳 404（而非 403），以避免洩露資源是否存在的資訊。

---

## 公開 Endpoint（不需認證）

以下路徑明確允許不帶 token 存取：

| 路徑                              | 說明                                |
|-----------------------------------|-------------------------------------|
| `POST /api/auth/login`            | 用戶登入                            |
| `GET /api/auth/me` (*)            | 未認證時回傳 401                    |
| `POST /api/auth/logout`           | 無狀態，永遠回傳 200                |
| `POST /api/admin/auth/login`      | 平台管理員登入                      |
| `GET /api/health`                 | 服務健康檢查                        |
| `GET /api/version`                | 服務版本資訊                        |
| `GET /api/actuator/**`            | Spring actuator endpoint            |
| `GET /api/user/channels/platforms`| 列出可用平台（公開）                |
| `GET /api/enums/**`               | Enum / 參考資料                     |

(*) `/api/auth/me` 技術上允許公開存取，但若未提供有效 token 則回傳 401。

---

## 登入 — 用戶帳號

```bash
curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"owner@acme-shop.com","password":"secret123"}' | jq .
```

### 成功回應（200 OK）

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJWNG5LcThtUjJ4THBZb1p3MUEzQiIsIm1lcmNoYW50SWQiOiJhMDAwMDAiLCJlbWFpbCI6Im93bmVyQGFjbWUtc2hvcC5jb20iLCJuYW1lIjoiQWxpY2UgQ2hlbiIsInJvbGUiOiJtYWluIiwiaWF0IjoxNzQzMTIwMDAwLCJleHAiOjE3NDM3MjQ4MDB9.SIGNATURE",
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

### 錯誤回應

| 情況                | HTTP 狀態碼 | 回應內容                                    |
|---------------------|-------------|---------------------------------------------|
| 缺少 email 或 password | 400      | `{"error":"email 和 password 為必填"}`      |
| 帳號不存在          | 401         | `{"error":"帳號或密碼錯誤"}`                |
| 密碼錯誤            | 401         | `{"error":"帳號或密碼錯誤"}`                |
| 帳號已停用          | 403         | `{"error":"帳號已停用"}`                    |

---

## 登入 — 平台管理員

```bash
curl -s -X POST http://localhost:8082/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@simpleec.com","password":"AdminSecret!"}' | jq .
```

### 成功回應（200 OK）

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": "PqR9sT2uV5wX8yZ0aB1C",
    "email": "admin@simpleec.com",
    "name": "Platform Admin",
    "role": "platform_admin"
  }
}
```

注意：管理員 token 的 `merchantId` 為空字串（`""`）。所有 `/api/admin/**` 操作可跨商家執行。

---

## 發送已認證請求

儲存 token 並在後續每個請求中帶入：

```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# 查詢訂單列表
curl -s http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer $TOKEN" | jq .

# 取得目前用戶資訊
curl -s http://localhost:8082/api/auth/me \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## 認證錯誤碼

| HTTP 碼 | 情境                                                                |
|---------|---------------------------------------------------------------------|
| 400     | 請求 body 缺少必填欄位（`email`、`password`）                       |
| 401     | 沒有 `Authorization` header；token 無效；token 過期；憑證錯誤       |
| 403     | token 有效但角色不足（例如非管理員呼叫 `/api/admin/**`）；帳號已停用 |

### 401 回應範例

```json
{
  "error": "帳號或密碼錯誤"
}
```

### 403 回應範例（角色不符）

Spring Security 在 `hasAuthority("ROLE_PLATFORM_ADMIN")` 驗證失敗時，會回傳 403 並附帶空的 body。

---

## CORS

API 允許來自以下來源的跨域請求（可透過 `simpleec.cors.allowed-origins` 設定）：

- `http://localhost:8080`
- `http://localhost:5173`
- `http://localhost:3000`

允許的方法：`GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`OPTIONS`
允許的 header：`Content-Type`、`Authorization`、`Accept`、`X-Requested-With`
憑證傳遞：允許（`allowCredentials: true`）
