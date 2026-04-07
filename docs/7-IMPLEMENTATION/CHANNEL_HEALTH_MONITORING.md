# Channel Health Monitoring 設計指南

> **目的**：說明通路健康檢查機制、Redis 快取策略、同步日誌 API。
>
> **最後更新**：2026-04-07
> **版本**：1.0

---

## 🎯 核心概念

Channel Health Monitoring 用於**監控各通路的健康狀態**，包括：
- API 連線是否正常
- 最後同步時間
- HTTP 狀態碼
- 錯誤訊息

---

## 🏗️ 架構設計

### 元件總覽

```
┌─────────────────────────────────────────────────────────┐
│  HealthCheckService (simpleec-channel-job)              │
│  ├─ 定期檢查各通路 API 連線                              │
│  ├─ 寫入 Redis 快取 (TTL 10 分鐘)                       │
│  └─ 記錄同步日誌到 channel_sync_logs 表                  │
├─────────────────────────────────────────────────────────┤
│  Redis 快取                                              │
│  ├─ Key: channel:health:{channelId}                     │
│  ├─ Value: JSON 健康狀態物件                            │
│  └─ TTL: 10 分鐘                                        │
├─────────────────────────────────────────────────────────┤
│  UserChannelController (simpleec-api)                   │
│  ├─ GET /api/user/channels/health-overview              │
│  ├─ GET /api/user/channels/{id}/sync-logs               │
│  └─ 從 Redis 讀取快取，避免頻繁檢查外部 API              │
├─────────────────────────────────────────────────────────┤
│  ChannelHealthOverview.vue (user-app)                   │
│  └─ 顯示所有通路的健康狀態表格                           │
└─────────────────────────────────────────────────────────┘
```

---

## 📋 Redis 快取設計

### Key 格式

```
channel:health:{channelId}
```

### Value 格式

```json
{
  "channelId": "ch_shopee_001",
  "channelName": "蝦皮官方店",
  "platformName": "Shopee",
  "healthy": true,
  "httpStatus": 200,
  "lastCheckTime": "2026-04-07T10:30:00Z",
  "errorMessage": null
}
```

### TTL 策略

- **TTL**: 10 分鐘
- **過期後**：下次健康檢查會重新寫入
- **好處**：避免頻繁檢查外部 API，同時保持資料新鮮

---

## 🔍 同步日誌 API

### GET /api/user/channels/{id}/sync-logs

**用途**：查詢指定通路的同步日誌（分頁）

**請求參數**：
| 參數 | 類型 | 必填 | 說明 |
|------|------|------|------|
| `id` | String | ✅ | 通路 ID |
| `page` | Integer | ❌ | 頁碼（預設 1） |
| `pageSize` | Integer | ❌ | 每頁筆數（預設 20） |

**回應格式**：
```json
{
  "code": 200,
  "data": [
    {
      "id": "log_xxx",
      "channelId": "ch_shopee_001",
      "syncType": "FETCH_ORDERS",
      "status": "success",
      "health": "healthy",
      "httpStatus": 200,
      "errorMessage": null,
      "createdAt": "2026-04-07T10:30:00Z"
    }
  ],
  "pagination": {
    "total": 100,
    "page": 1,
    "pageSize": 20
  }
}
```

**權限**：僅限通路擁有者（merchantId 比對）

---

## 📊 健康狀態總覽 API

### GET /api/user/channels/health-overview

**用途**：取得所有通路的健康狀態（從 Redis 快取）

**回應格式**：
```json
{
  "code": 200,
  "data": [
    {
      "channelId": "ch_shopee_001",
      "channelName": "蝦皮官方店",
      "platformName": "Shopee",
      "healthy": true,
      "httpStatus": 200,
      "lastCheckTime": "2026-04-07T10:30:00Z",
      "errorMessage": null
    }
  ]
}
```

---

## 🖥️ 前端元件

### ChannelHealthOverview.vue

**用途**：顯示所有通路的健康狀態表格

**功能**：
- 顯示通路名稱、平台、啟用狀態
- 健康狀態（healthy / unhealthy）
- HTTP 狀態碼
- 最後檢查時間
- 錯誤訊息
- 手動重新整理按鈕

**使用方式**：
```vue
<template>
  <ChannelHealthOverview />
</template>

<script setup>
import ChannelHealthOverview from '@/components/ChannelHealthOverview.vue'
</script>
```

### ChannelPage 同步日誌 Drawer

**用途**：顯示單一通路的同步日誌

**功能**：
- 分頁查詢（20/50/100 筆）
- 顯示 syncType、狀態、健康、HTTP、錯誤、時間
- 從通路卡片的「日誌」按鈕開啟

---

## 🔧 HealthCheckService 實作

### 健康檢查流程

```java
public void checkChannelHealth(Channel channel) {
    String channelId = channel.getId();
    HealthResult result;
    
    try {
        // 1. 呼叫平台 API 測試連線
        int httpStatus = testConnection(channel);
        
        // 2. 判斷健康狀態
        boolean healthy = httpStatus >= 200 && httpStatus < 300;
        String health = healthy ? "healthy" : "unhealthy";
        
        // 3. 寫入 Redis 快取
        cacheHealthResult(channelId, channel, healthy, httpStatus, null);
        
        // 4. 記錄同步日誌
        logHealth(channel, health, httpStatus, null);
        
    } catch (Exception e) {
        // 失敗處理
        boolean healthy = false;
        String health = "unhealthy";
        int httpStatus = 0;
        String errorMessage = e.getMessage();
        
        // 寫入 Redis 和日誌
        cacheHealthResult(channelId, channel, healthy, httpStatus, errorMessage);
        logHealth(channel, health, httpStatus, errorMessage);
    }
}
```

### Redis 快取寫入

```java
private void cacheHealthResult(String channelId, Channel channel, 
                                boolean healthy, int httpStatus, 
                                String errorMessage) {
    try {
        ObjectNode healthJson = objectMapper.createObjectNode();
        healthJson.put("channelId", channelId);
        healthJson.put("channelName", channel.getChannelName());
        healthJson.put("platformName", channel.getPlatformName());
        healthJson.put("healthy", healthy);
        healthJson.put("httpStatus", httpStatus);
        healthJson.put("lastCheckTime", Instant.now().toString());
        healthJson.put("errorMessage", errorMessage != null ? errorMessage : "");
        
        String key = "channel:health:" + channelId;
        redisTemplate.opsForValue().set(key, healthJson.toString(), 10, TimeUnit.MINUTES);
    } catch (Exception e) {
        log.error("Failed to cache health result for channel: {}", channelId, e);
    }
}
```

---

## 📊 健康狀態定義

| 狀態 | 條件 | 說明 |
|------|------|------|
| `healthy` | HTTP 2xx | API 連線正常 |
| `unhealthy` | HTTP 4xx/5xx 或異常 | API 連線失敗 |

---

## 🚀 部署注意事項

### 環境變數

無需額外環境變數，使用既有的 Redis 連線配置。

### Redis 記憶體預估

- 每個通路健康狀態物件：~200 bytes
- 假設 50 個通路：50 × 200 = 10 KB
- TTL 10 分鐘自動過期，記憶體使用量低

### 監控建議

1. **Redis 命中率**：應 > 90%
2. **健康檢查頻率**：建議每 5-10 分鐘
3. **錯誤警報**：連續 3 次 unhealthy 發送警報

---

## ⚠️ 注意事項

1. **Redis 快取可能過期**：UI 顯示「最後檢查時間」讓用戶知道資料新舊
2. **健康檢查不影響主要流程**：即使健康檢查失敗，訂單同步仍正常執行
3. **錯誤訊息可能包含敏感資訊**：生產環境應遮罩敏感資訊

---

## 🔗 相關文檔

- [Platform Capabilities Guide](PLATFORM_CAPABILITIES_GUIDE.md)
- [Shopee OAuth Guide](SHOPEE_OAUTH_GUIDE.md)
- [Channel Implementation Guide](CHANNEL_IMPLEMENTATION_GUIDE.md)
- [Redis Deduplication](docs/3-EVENT-FLOW/REDIS_DEDUPLICATION.md)

---

**維護者**: Tom
**最後更新**: Apr 7, 2026
