# Shopee OAuth 整合指南

> 適用對象：需要為新平台實作 OAuth / Token 管理的開發者

---

## 0. 背景與設計原則

Shopee Open Platform 使用 OAuth 2.0 授權流程。與 API Key 型平台（如 Cyberbiz）不同，Shopee 的 token 有 4 小時過期，且 refresh_token 使用後會輪轉（一次性）。

**核心設計原則：**
- `Platform` 表存 **App 層憑證**（partner_id, partner_key）—— 全局唯一
- `Channel` 表的 token1~4 存 **Shop 層憑證**（每個店鋪各自的 token）
- Token 刷新採 **Proactive（每小時 BackendJob）+ Reactive（Redis lock）** 混合策略
- OAuth callback 用 **popup window + postMessage** 避免離開 Channel 頁面

---

## 1. 資料庫欄位語意（Shopee 專用）

### Platform 表（credential1, credential2）

| 欄位 | 語意 | 說明 |
|------|------|------|
| `credential1` | `partner_id` | Shopee 開發者後台拿到的數字 ID |
| `credential2` | `partner_key` | HMAC 簽名用的 secret key |
| `capabilities.oauthFlow` | `"shopee_oauth"` | 告訴前端這是 OAuth 平台 |
| `capabilities.tokenLabels` | JSON object | 定義 token1~5 的顯示名稱 |

**capabilities 範例（在 DB 直接設定）：**
```json
{
  "oauthFlow": "shopee_oauth",
  "tokenLabels": {
    "token1": "Access Token",
    "token2": "Refresh Token",
    "token3": "Shop ID",
    "token4": "Token 到期時間",
    "token5": null
  },
  "multiLocation": false,
  "webhook": true
}
```

### Channel 表（token1~4）

| 欄位 | 語意 | TTL |
|------|------|-----|
| `token` | `access_token` | 4 小時（BackendJob 每小時刷新） |
| `token2` | `refresh_token` | 30 天（使用後輪轉，立即存新值） |
| `token3` | `shop_id` | 永久（授權時設定，不變） |
| `token4` | `token_expires_at` (ISO-8601) | 用於到期判斷 |

---

## 2. Shopee HMAC-SHA256 簽名規則

每個 API call 都需要簽名。**注意：中間無任何分隔符，純串接。**

```
# Public API（auth URL 生成、code exchange）
base_string = partner_id + path + timestamp

# Shop API（所有訂單/商品/出貨 API）
base_string = partner_id + path + timestamp + access_token + shop_id
```

**Java 實作：**
```java
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;

private String sign(String partnerKey, String partnerId, String path,
                    long timestamp, String accessToken, String shopId) {
    StringBuilder sb = new StringBuilder();
    sb.append(partnerId).append(path).append(timestamp);
    if (accessToken != null) sb.append(accessToken);
    if (shopId != null) sb.append(shopId);
    return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, partnerKey).hmacHex(sb.toString());
}
```

---

## 3. OAuth 授權流程（4 步驟）

### Step 1：生成授權 URL

```
GET /api/user/channels/{channelId}/shopee/auth-url
→ { "authUrl": "https://partner.shopeemobile.com/api/v2/shop/auth_partner?..." }
```

後端邏輯（`ShopeeOAuthService.generateAuthUrl`）：
1. 從 `Platform` 表讀取 `credential1`（partner_id）、`credential2`（partner_key）
2. 生成 `state = NanoIdUtil.generate()`
3. 存 Redis：`shopee:oauth:state:{state}` → `channelId`，TTL 10 分鐘
4. 計算公開 API 簽名（無 access_token）
5. 回傳完整授權 URL（含 `redirect={callbackUrl}?state={state}`）

### Step 2：前端開 popup 視窗

```javascript
// 前端
const { authUrl } = await api.get(`/user/channels/${channelId}/shopee/auth-url`);
const popup = window.open(authUrl, '_blank', 'popup,width=600,height=700');

// 監聽授權結果
window.addEventListener('message', (event) => {
  if (event.data?.type === 'shopee_oauth_success') {
    reloadChannelData(); // 刷新頁面資料，不換路由
  }
  if (event.data?.type === 'shopee_oauth_error') {
    showError(event.data.message);
  }
});
```

### Step 3：Shopee callback

蝦皮授權完成後，redirect 到：
```
GET /callback/shopee?code={code}&shop_id={shop_id}&state={state}
```

後端邏輯（`ShopeeCallbackController`→`ShopeeOAuthService.handleCallback`）：
1. 從 Redis 取 `shopee:oauth:state:{state}` → `channelId`（驗證 state，防 CSRF）
2. 刪除 Redis state key
3. POST `/api/v2/auth/token/get` 換取 token
4. 存入 Channel：`token=access_token, token2=refresh_token, token3=shop_id, token4=expires_at`
5. 回傳 HTML，關閉 popup 視窗

### Step 4：前端收到 postMessage

popup 關閉，主頁面收到 `shopee_oauth_success` 訊息，重新讀 Channel 資料（不換路由）。

---

## 4. Token 生命週期管理

### Proactive 刷新（主路徑）

每小時整點，`SchedulerEventHandler` 派發 `SHOPEE_TOKEN_REFRESH`（`minuteOfHour == 0`）。

`ShopeeTokenRefreshHandler`（BackendJob）執行：
1. 查詢所有 `platform_id='shopee', actived=true` 的 Channel
2. 過濾 `token_expires_at < now + 90分鐘`
3. 每個 channel 執行 `refreshWithLock(channelId)`

**為什麼是 90 分鐘？**
- Access token 有效 4 小時
- 每小時刷新一次 → 最差情況：剛刷完就 skip，下次在 1 小時後
- 90 分鐘 buffer 確保不會卡在邊界

### Reactive 刷新（備援）

任何 API call 失敗（token expired）可以呼叫：
```
POST /api/user/channels/{id}/shopee/refresh-token
```
這會執行 `forceRefreshToken`（先清 lock，再刷）。

### Redis Distributed Lock

```
Key:  shopee:token:refresh:lock:{channelId}
Value: "1"
TTL:  30 秒（防死鎖）
```

同一 channelId 同一時間只有一個 instance 在刷新。其他 instance 拿不到 lock 就 skip，
等下次排程或直接用 DB 最新 token。

---

## 5. API 端點速查

| Method | Path | 說明 | Auth |
|--------|------|------|------|
| GET | `/api/user/channels` | 列出商家所有通路（ChannelVO，token 已遮罩） | JWT |
| GET | `/api/user/channels/{id}` | 取單一通路詳情 | JWT |
| PUT | `/api/user/channels/{id}` | 更新通路（含手動設定 token1~5） | JWT |
| GET | `/api/user/channels/{id}/shopee/auth-url` | 生成 Shopee OAuth URL | JWT |
| POST | `/api/user/channels/{id}/shopee/refresh-token` | 手動強制刷新 token | JWT |
| POST | `/api/user/channels/{id}/shopee/disconnect` | 斷開授權，清空 token | JWT |
| GET | `/callback/shopee` | Shopee OAuth callback（公開） | 無 |

---

## 6. ChannelVO（前端 API 回傳格式）

```json
{
  "id": "abc123",
  "channelName": "我的蝦皮店鋪",
  "platformId": "shopee",
  "platformName": "Shopee",
  "actived": true,
  "enableSync": true,

  // Token 遮罩（前4...後4，空值為 null）
  "token1Masked": "eyJh...3mRt",
  "token2Masked": "aB7k...9pQz",
  "token3Masked": "1234...5678",
  "token4Masked": "2026...00Z",
  "token5Masked": null,

  // Platform capabilities 衍生
  "oauthFlow": "shopee_oauth",
  "tokenLabels": {
    "token1": "Access Token",
    "token2": "Refresh Token",
    "token3": "Shop ID",
    "token4": "Token 到期時間",
    "token5": null
  },

  // OAuth 狀態（僅 oauthFlow=shopee_oauth 才有）
  "oauthStatus": "CONNECTED",       // NOT_CONNECTED | CONNECTED | EXPIRING_SOON | EXPIRED
  "tokenExpiresAt": "2026-04-06T12:00:00Z",
  "tokenExpiresInMinutes": 45
}
```

**oauthStatus 轉換邏輯：**

| 條件 | oauthStatus |
|------|-------------|
| `token` 為空 | `NOT_CONNECTED` |
| `token4` 為空 | `CONNECTED`（樂觀假設） |
| `token_expires_at > now + 60min` | `CONNECTED` |
| `0 < token_expires_at - now ≤ 60min` | `EXPIRING_SOON` |
| `token_expires_at ≤ now` | `EXPIRED` |

---

## 7. 新增其他 OAuth 平台（如 Lazada、LINE）

依照相同模式：

### Step 1：DB 設定
在 `platform` 表設定 `capabilities`：
```json
{
  "oauthFlow": "lazada_oauth",
  "tokenLabels": {
    "token1": "Access Token",
    "token2": "Refresh Token",
    "token3": "Seller ID",
    "token4": "Token 到期時間"
  }
}
```

### Step 2：新增 OAuthService
複製 `ShopeeOAuthService`，改：
- `SHOPEE_PARTNER_BASE` → 目標平台 base URL
- `AUTH_PATH`, `TOKEN_GET_PATH`, `TOKEN_REFRESH_PATH` → 對應路徑
- `hmacSign` → 目標平台的簽名算法（可能不是 HMAC-SHA256）

### Step 3：新增 CallbackController
複製 `ShopeeCallbackController`，改 `@RequestMapping` path 和 service 注入。

### Step 4：在 SecurityConfig 加 permitAll
```java
"/callback/shopee",
"/callback/lazada",   // 新增
```

### Step 5：在 UserChannelController 加端點
新增 `/{id}/lazada/auth-url`, `/{id}/lazada/refresh-token`, `/{id}/lazada/disconnect`。

### Step 6：新增 TokenRefreshHandler（BackendJob）
複製 `ShopeeTokenRefreshHandler`，改 platform 名稱和 API 路徑。

### Step 7：SchedulerEventHandler 加 dispatch
```java
if (minuteOfHour == 0) {
    dispatchTask(TaskTypeEnum.SHOPEE_TOKEN_REFRESH, timestamp);
    dispatchTask(TaskTypeEnum.LAZADA_TOKEN_REFRESH, timestamp);  // 新增
}
```

### Step 8：TaskTypeEnum 加新 entry
```java
LAZADA_TOKEN_REFRESH("LAZADA_TOKEN_REFRESH", "Lazada OAuth Token 刷新", false),
```

---

## 8. 常見問題

**Q: refresh_token 用完了怎麼辦？**  
Shopee 的 refresh_token 每次使用後輪轉（`doRefresh` 會存 new refresh_token 到 `token2`）。
如果 30 天都沒有任何活動（也沒有刷新），refresh_token 失效 → 需要重新授權（UI 顯示 `EXPIRED`，點「重新授權」）。

**Q: 前端可以看到完整 token 嗎？**  
不行。API 永遠回傳遮罩版本（`token1Masked` 等）。
如果商家需要驗證 token，提供 `[👁]` 按鈕 → 呼叫一個帶 JWT 的查詢 API 回傳完整值（該 API 不在目前實作範圍內）。

**Q: 多個 simpleec-backend-job instance 同時跑 SHOPEE_TOKEN_REFRESH 怎麼辦？**  
每個 channel 有 Redis lock（TTL 30s）。第一個拿到 lock 的 instance 刷新，其他 instance skip 那個 channel。

**Q: 測試環境 / Sandbox 怎麼換？**  
改 `SHOPEE_CALLBACK_URL` env var，並把 `ShopeeOAuthService.SHOPEE_PARTNER_BASE` 換成 Sandbox URL：
```
https://openplatform.sandbox.test-stable.shopee.sg
```

**Q: 怎麼設定 Shopee 開發者後台的 Redirect URL？**  
在 Shopee Partner Center → App 設定 → Redirect URL 填入：
```
https://your-domain.com/callback/shopee
```
本地開發可用 ngrok tunnel：`ngrok http 8082`

---

## 9. 相關程式碼位置

| 功能 | 檔案 |
|------|------|
| OAuth service | `simpleec-api/.../service/ShopeeOAuthService.java` |
| Callback endpoint | `simpleec-api/.../controller/ShopeeCallbackController.java` |
| Channel API + OAuth endpoints | `simpleec-api/.../controller/UserChannelController.java` |
| Channel VO（token 遮罩） | `simpleec-api/.../vo/ChannelVO.java` |
| BackendJob 刷新 handler | `simpleec-backend-job/.../handler/impl/ShopeeTokenRefreshHandler.java` |
| Scheduler dispatch | `simpleec-scheduler-job/.../handler/SchedulerEventHandler.java` |
| TaskType 定義 | `simpleec-common/.../enums/TaskTypeEnum.java` |
| Security config（permitAll） | `simpleec-api/.../security/SecurityConfig.java` |
| Shopee callback URL config | `simpleec-api/.../resources/application.yml` |
