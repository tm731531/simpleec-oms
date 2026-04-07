# Platform Capabilities 設計指南

> **目的**：說明如何使用 `capabilities` JSONB 欄位驅動業務邏輯，避免 hardcode 平台名稱判斷。
>
> **最後更新**：2026-04-07
> **版本**：1.0

---

## 🎯 核心概念

`platform.capabilities` 是一個 JSONB 欄位，用於定義平台的**能力旗標**和**配置資訊**。

### 設計原則

1. **避免 hardcode 平台名稱**：不要寫 `if (platformName.equals("shopee"))`
2. **用 capabilities 驅動邏輯**：改為 `if (capabilities.path("oauthFlow").asText().equals("shopee_oauth"))`
3. **可擴充**：新增平台只需設定 capabilities，不需修改程式碼

---

## 📋 capabilities JSON 結構

```json
{
  "oauthFlow": "shopee_oauth",
  "multiLocation": false,
  "webhook": true,
  "asyncInventory": false,
  "tokenLabels": {
    "token1": "Access Token",
    "token2": "Refresh Token",
    "token3": "Shop ID",
    "token4": "Token 到期時間",
    "token5": null
  }
}
```

### 欄位說明

| 欄位 | 類型 | 說明 | 範例 |
|------|------|------|------|
| `oauthFlow` | String | OAuth 流程類型 | `shopee_oauth`, `null`（無 OAuth） |
| `multiLocation` | Boolean | 多倉庫支援 | `true` = 庫存需按 location 管理 |
| `webhook` | Boolean | Webhook 支援 | `true` = 平台主動推送事件 |
| `asyncInventory` | Boolean | 非同步庫存 | `true` = 庫存更新需等待回調 |
| `tokenLabels` | Object | Token 欄位名稱自訂 | 見下方說明 |

---

## 🔑 Token Labels 設計

### 用途

`tokenLabels` 定義了通路管理頁面中 token1~token5 的**顯示名稱**。

### 規則

1. **有設定**：顯示自訂名稱（如 "Access Token"）
2. **設為 null**：不使用該欄位（不會顯示在 UI）
3. **完全沒有 tokenLabels**：顯示預設名稱（"Token 1", "Token 2"...）

### 範例

```json
{
  "tokenLabels": {
    "token1": "Access Token",
    "token2": "Refresh Token",
    "token3": "Shop ID",
    "token4": "Token 到期時間",
    "token5": null
  }
}
```

**UI 顯示**：
- Token 1: "Access Token"
- Token 2: "Refresh Token"
- Token 3: "Shop ID"
- Token 4: "Token 到期時間"
- Token 5: **不顯示**（因為是 null）

---

## 🔐 OAuth Flow 設計

### 支援的 OAuth 類型

| 值 | 說明 | Channel 頁面行為 |
|----|------|-----------------|
| `shopee_oauth` | Shopee OAuth 2.0 | 顯示「連結蝦皮」、「刷新 Token」、「解除授權」按鈕 |
| `null` 或不存在 | 無 OAuth | 不顯示 OAuth 相關按鈕 |

### Channel 頁面判斷邏輯

```typescript
// user-app/src/views/ChannelPage.vue
const formOAuthFlow = computed(() => {
  if (editingChannel.value) return editingChannel.value.oauthFlow
  return selectedPlatform.value?.capabilities?.oauthFlow ?? null
})

// 只在有 oauthFlow 時顯示 OAuth 按鈕區
<el-form-item v-if="formOAuthFlow && editingChannel" label="OAuth 授權">
  ...
</el-form-item>
```

---

## 🏗️ 平台能力旗標

### multiLocation

**用途**：判斷平台是否有倉庫/位置概念

```java
// 範例：庫存同步邏輯
if (platform.getCapabilities().path("multiLocation").asBoolean(false)) {
    // 多倉庫模式：庫存需按 location 管理
    syncInventoryByLocation(channel);
} else {
    // 單一庫存模式
    syncInventory(channel);
}
```

**已知使用平台**：Shopify

### webhook

**用途**：判斷平台是否支援主動推送事件

```java
// 範例：訂單同步策略
if (platform.getCapabilities().path("webhook").asBoolean(false)) {
    // Webhook 模式：被動接收事件
    setupWebhookEndpoint(channel);
} else {
    // Polling 模式：主動拉取訂單
    scheduleFetchOrders(channel);
}
```

**已知使用平台**：Shopee、Shopify

### asyncInventory

**用途**：判斷平台庫存更新是否為非同步

```java
// 範例：庫存更新邏輯
if (platform.getCapabilities().path("asyncInventory").asBoolean(false)) {
    // 非同步模式：發送請求後等待回調
    sendInventoryUpdateRequest(channel);
    // 不立即更新本地庫存，等待 webhook 回調
} else {
    // 同步模式：直接更新
    updateInventoryDirect(channel);
}
```

---

## 📝 設定方式

### 透過 admin-app UI

1. 登入 admin-app（`http://oms-admin.tomting.com`）
2. 進入「平台管理」
3. 點擊「編輯」或「新增平台」
4. 設定以下欄位：
   - **OAuth 類型**：下拉選單（無 / Shopee OAuth）
   - **平台能力**：勾選（多倉庫、Webhook、非同步庫存）
   - **Token 欄位名稱**：輸入 token1~token5 的顯示名稱

### 直接寫入資料庫

```sql
UPDATE platform SET capabilities = '{
  "oauthFlow": "shopee_oauth",
  "multiLocation": false,
  "webhook": true,
  "asyncInventory": false,
  "tokenLabels": {
    "token1": "Access Token",
    "token2": "Refresh Token",
    "token3": "Shop ID",
    "token4": "Token 到期時間",
    "token5": null
  }
}'::jsonb
WHERE id = 'shopee';
```

---

## 🔍 讀取 capabilities

### Java 後端

```java
// Platform entity
JsonNode caps = platform.getCapabilities();

// 讀取 oauthFlow
String oauthFlow = caps.path("oauthFlow").asText();

// 讀取 boolean（預設 false）
boolean multiLocation = caps.path("multiLocation").asBoolean(false);

// 讀取 tokenLabels
JsonNode tokenLabels = caps.path("tokenLabels");
String token1Label = tokenLabels.path("token1").asText("Token 1");
```

### TypeScript 前端

```typescript
// Platform interface
interface Platform {
  capabilities?: Record<string, any>
}

// 讀取 oauthFlow
const oauthFlow = platform.capabilities?.oauthFlow

// 讀取 tokenLabels
const tokenLabels = platform.capabilities?.tokenLabels
const token1Label = tokenLabels?.token1 ?? 'Token 1'
```

---

## 📊 現有平台設定

| 平台 | oauthFlow | multiLocation | webhook | asyncInventory | tokenLabels |
|------|-----------|---------------|---------|----------------|-------------|
| Shopee | `shopee_oauth` | false | true | false | ✅ 自訂 |
| Shopify | null | true | true | false | ❌ 預設 |
| Cyberbiz | null | false | false | false | ❌ 預設 |
| MOMO | null | false | false | false | ❌ 預設 |
| Yahoo | null | false | false | false | ❌ 預設 |
| PChome | null | false | false | false | ❌ 預設 |
| Shopline | null | false | false | false | ❌ 預設 |

---

## 🚀 新增平台指南

### 步驟

1. **在 admin-app 新增平台**
   - 平台 ID：`my_new_platform`
   - 平台名稱：`My New Platform`
   - 設定 capabilities

2. **設定 capabilities**
   ```json
   {
     "oauthFlow": null,
     "multiLocation": false,
     "webhook": false,
     "asyncInventory": false,
     "tokenLabels": {
       "token1": "API Key",
       "token2": "API Secret",
       "token3": null,
       "token4": null,
       "token5": null
     }
   }
   ```

3. **實作 ChannelAdapter**
   - 參考 `docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md`

4. **部署**
   - 不需修改任何 handler 程式碼

---

## ⚠️ 注意事項

1. **capabilities 不可為 null**：至少要是空物件 `{}`
2. **tokenLabels 的 null 有意義**：表示不使用該欄位
3. **oauthFlow 變更會影響 UI**：Channel 頁面會顯示/隱藏 OAuth 按鈕
4. **capabilities 變更不需重啟**：即時生效

---

## 🔗 相關文檔

- [Channel Health Monitoring](CHANNEL_HEALTH_MONITORING.md)
- [Shopee OAuth Guide](SHOPEE_OAUTH_GUIDE.md)
- [Channel Implementation Guide](CHANNEL_IMPLEMENTATION_GUIDE.md)
- [Capabilities Model Rules](docs/rules/tech/capabilities-model.md)

---

**維護者**: Tom
**最後更新**: Apr 7, 2026
