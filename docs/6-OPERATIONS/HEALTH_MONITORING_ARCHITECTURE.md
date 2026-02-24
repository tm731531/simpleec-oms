# Health Monitoring System Architecture

## 系統概述

SimpleEC OMS 健康監控系統是一個完整的多層次監控解決方案，用於實時追蹤商家通路和電商平台的連接狀態和API可用性。

### 核心目標
- **實時監控**: 每5分鐘自動檢查所有啟用的通路和平台
- **故障診斷**: 通過HTTP狀態碼快速定位問題（401=令牌失效、403=權限不足、500=平台錯誤）
- **歷史追蹤**: 記錄完整的健康檢查歷史供審計和分析
- **視覺化展示**: 前端儀表板顯示實時狀態和趨勢

---

## 架構層次

### 1️⃣ 調度層 (Scheduler Layer)

**位置**: `simpleec-scheduler-job` 服務

**主要組件**:
- `HealthCheckScheduler.java` - 核心調度器
  - @Scheduled(fixedRate = 300000ms) - 每5分鐘執行一次
  - 查詢所有啟用的通路 (enableSync = true)
  - 查詢所有活躍的平台 (actived = true)
  - 發布健康檢查任務到Kafka

**輸出消息**:
```json
// 通路級檢查 (HealthCheckMessage)
{
  "taskType": "CHECK_HEALTH",
  "merchantId": "merchant-001",
  "channelId": "channel-001",
  "platformCode": "shopee",
  "timestamp": 1708663200000
}

// 平台級檢查 (PlatformHealthCheckMessage)
{
  "taskType": "CHECK_HEALTH_PLATFORM",
  "platformCode": "shopee",
  "timestamp": 1708663200000
}
```

**發布到Kafka**:
- `{platformCode}.fast` - 所有7個平台都有fast主題
- 消息鍵: channelId 或 platformCode

---

### 2️⃣ 服務層 (Service Layer)

**位置**: `simpleec-channel-job` 服務

#### HealthCheckService
**職責**: 執行實際的健康檢查並記錄結果

**方法**:
```java
HealthCheckResult performChannelHealthCheck(String channelId)
- 檢索通路配置 (包含 merchantId, platformCode, token)
- 呼叫 platformApiClient.healthCheck()
- 記錄結果到 channel_sync_logs
- 返回 HealthCheckResult (httpStatus, health, errorMessage)

HealthCheckResult performPlatformHealthCheck(String platformCode)
- 呼叫 platformApiClient.platformHealthCheck()
- 記錄結果到 channel_sync_logs (channelId=null)
- 返回 HealthCheckResult
```

#### PlatformApiClient (Interface & Implementation)
**職責**: 實際調用平台API進行健康檢查

**映射表**:
| 平台 | 認證檢查端點 | 平台檢查端點 |
|------|-----------|-----------|
| shopee | https://api.shopee.com/health | https://api.shopee.com/status |
| momo | https://api.payment.momo.com/health | https://api.payment.momo.com/status |
| yahoo | https://api.yahoo.tw/health | https://api.yahoo.tw/status |
| pchome | https://api.pchome.com.tw/health | https://api.pchome.com.tw/status |
| cyberbiz | https://api.cyberbiz.com/health | https://api.cyberbiz.com/status |
| shopline | https://api.shopline.com/health | https://api.shopline.com/status |
| shopify | https://api.shopify.com/health | https://api.shopify.com/status |

**配置**:
- ConnectTimeout: 5秒
- ReadTimeout: 10秒
- 異常處理:
  - RestClientException (HTTP錯誤) → 500
  - 其他異常 → 503

---

### 3️⃣ 持久化層 (Persistence Layer)

**表**: `channel_sync_logs`

**字段**:
```sql
id              VARCHAR(36)        -- UUID
channel_id      VARCHAR(50)        -- NULL for platform-level checks
merchant_id     VARCHAR(50)        -- NULL for platform-level checks
platform_code   VARCHAR(50)        -- 平台代碼
http_status     INT                -- HTTP狀態碼 (200, 401, 403, 500, 503等)
status          VARCHAR(50)        -- "success" 或 "failed"
error_message   TEXT               -- 錯誤描述 (401=令牌失效等)
created_at      TIMESTAMP          -- 檢查時間
```

**查詢**:
- `findByChannelId(channelId, pageable)` - 特定通路的檢查歷史
- `findByChannelIdIsNullOrderByCreatedAtDesc(pageable)` - 平台級檢查歷史
- `findTop100ByOrderByCreatedAtDesc()` - 最近100筆檢查 (用於摘要)

---

### 4️⃣ REST API層 (API Layer)

**位置**: `simpleec-channel-job` 服務

**路由**: `/api/health`

#### 端點列表

##### 1. 通路健康狀態查詢
```
GET /api/health/channel/{channelId}

Response:
{
  "httpStatus": 200,
  "health": "healthy",
  "errorMessage": null
}
```

##### 2. 平台健康狀態查詢
```
GET /api/health/platform/{platformCode}

Response:
{
  "httpStatus": 200,
  "health": "healthy",
  "errorMessage": null
}
```

##### 3. 通路檢查歷史
```
GET /api/health/channel/{channelId}/history?page=0&size=10

Response:
{
  "channelId": "channel-001",
  "totalRecords": 150,
  "page": 0,
  "size": 10,
  "logs": [
    {
      "id": "...",
      "channelId": "channel-001",
      "merchantId": "merchant-001",
      "httpStatus": 200,
      "status": "success",
      "errorMessage": null,
      "createdAt": "2026-02-23T11:30:00"
    },
    ...
  ]
}
```

##### 4. 平台檢查歷史
```
GET /api/health/platform/{platformCode}/history?page=0&size=10

Response:
{
  "platformCode": "shopee",
  "totalRecords": 200,
  "page": 0,
  "size": 10,
  "logs": [...]
}
```

##### 5. 健康摘要
```
GET /api/health/summary

Response:
{
  "totalChecks": 5000,
  "recentChecks": 100,
  "healthyCount": 95,
  "unhealthyCount": 5,
  "healthPercentage": 95.0
}
```

---

### 5️⃣ 前端層 (Frontend Layer)

**位置**: `user-app` 服務

**組件**: `HealthDashboard.vue`

**路由**: `/health`

**功能**:
1. **健康摘要區域**
   - 總檢查次數
   - 最近檢查次數
   - 健康/不健康計數
   - 健康百分比

2. **通路狀態查詢**
   - 搜尋框 (通路ID)
   - 實時狀態顯示
   - 檢查歷史表格 (分頁)

3. **平台卡片**
   - 7個平台的實時狀態卡片
   - 綠色 (healthy) / 紅色 (unhealthy)
   - 點擊查看歷史按鈕

4. **歷史模態框**
   - 詳細的檢查歷史表格
   - HTTP狀態碼
   - 錯誤消息

---

## 數據流

```
┌─────────────────────┐
│  HealthCheckScheduler │ (每5分鐘執行)
└──────────┬──────────┘
           │
           ├─ 查詢啟用的通路
           ├─ 查詢活躍的平台
           │
           ├─ 發布 HealthCheckMessage → Kafka/{platform}.fast
           └─ 發布 PlatformHealthCheckMessage → Kafka/{platform}.fast
                    │
                    ▼
           ┌──────────────────────┐
           │  Channel-Job 消費者   │
           │ (接收健康檢查任務)    │
           └──────────┬───────────┘
                    │
                    ├─ HealthCheckService.performChannelHealthCheck()
                    │           │
                    │           ├─ 查詢 Channel (token)
                    │           ├─ PlatformApiClient.healthCheck()
                    │           └─ 記錄日誌 → DB
                    │
                    └─ HealthCheckService.performPlatformHealthCheck()
                            │
                            ├─ PlatformApiClient.platformHealthCheck()
                            └─ 記錄日誌 → DB
                                     │
                                     ▼
                        ┌────────────────────────┐
                        │ channel_sync_logs 表   │
                        │ (健康檢查歷史記錄)     │
                        └────────────┬───────────┘
                                     │
                         ┌───────────┴──────────┐
                         │                      │
                         ▼                      ▼
                  ┌──────────────┐    ┌──────────────────┐
                  │ REST API     │    │ 前端儀表板        │
                  │ (5個端點)    │    │ HealthDashboard  │
                  └──────────────┘    └──────────────────┘
```

---

## 狀態碼解釋

| HTTP狀態 | 健康狀態 | 含義 | 建議動作 |
|---------|--------|------|---------|
| 200 | ✅ Healthy | API正常 | 無需操作 |
| 201-299 | ✅ Healthy | 成功但非200 | 無需操作 |
| 300-399 | ⚠️ Warning | 重定向 | 檢查API配置 |
| 401 | ❌ Unhealthy | 令牌失效/過期 | 重新授權或更新令牌 |
| 403 | ❌ Unhealthy | 權限不足 | 檢查API權限配置 |
| 404 | ❌ Unhealthy | API端點不存在 | 檢查平台API文檔 |
| 429 | ❌ Unhealthy | 超過速率限制 | 降低檢查頻率 |
| 500 | ❌ Unhealthy | 平台服務錯誤 | 聯繫平台支持 |
| 503 | ❌ Unhealthy | 平台服務不可用 | 等待平台恢復 |

---

## 支持的平台 (7個MVP平台)

| 平台 | 複雜度 | 狀態 | 檢查端點 |
|------|------|------|---------|
| Cyberbiz | Easy | ✅ | https://api.cyberbiz.com/health |
| PChome | Easy | ✅ | https://api.pchome.com.tw/health |
| MOMO | Medium | ✅ | https://api.payment.momo.com/health |
| Shopline | Medium | ✅ | https://api.shopline.com/health |
| Yahoo購物中心 | Medium | ✅ | https://api.yahoo.tw/health |
| Shopee | Hard | ✅ | https://api.shopee.com/health |
| Shopify | Hard | ✅ | https://api.shopify.com/health |

---

## 監控指標

### 關鍵指標
- **健康百分比** = (健康檢查數 / 總檢查數) × 100%
  - ≥ 95% = 優秀 (綠色)
  - ≥ 80% = 良好 (黃綠)
  - ≥ 50% = 警告 (橙色)
  - < 50% = 危急 (紅色)

### 檢查間隔
- 基礎間隔: **5分鐘** (300秒)
- 可設定: 修改 `@Scheduled(fixedRate = xxxx)` 值

### 日誌保留
- **Kafka**: 1小時 (KAFKA_LOG_RETENTION_HOURS)
- **數據庫**: 無限期 (管理員定期清理)
- **建議**: 保留30天歷史數據用於分析

---

## 技術棧

| 層 | 技術 | 版本 |
|----|------|------|
| 調度 | Spring Boot | 3.5.0 |
| 消息隊列 | Kafka | 3.7.1 |
| 持久化 | PostgreSQL | 16 |
| REST API | Spring Web | 3.5.0 |
| 前端框架 | Vue 3 + Vite | 3/7 |
| UI組件庫 | Element Plus | 4.0 |
| HTTP客戶端 | RestTemplate | Spring Web |

---

## 擴展性考慮

### 添加新平台
1. 在 `config.yaml` 中添加平台配置
2. 在 `PlatformApiClientImpl.java` 中添加端點映射
3. 在 `HealthDashboard.vue` 中添加平台卡片
4. 測試健康檢查流程

### 調整檢查頻率
修改 `HealthCheckScheduler.java`:
```java
@Scheduled(fixedRate = 600000)  // 改為10分鐘
```

### 改變超時設定
修改 `RestTemplateConfig.java`:
```java
.setConnectTimeout(Duration.ofSeconds(10))
.setReadTimeout(Duration.ofSeconds(20))
```

---

## 故障排除快速指南

| 問題 | 原因 | 解決方案 |
|------|------|---------|
| 沒有檢查記錄 | Scheduler未啟動 | 檢查Spring Boot日誌 |
| 所有平台都是紅色 | 平台API不可達 | 檢查網路連接和防火牆 |
| 間歇性紅色 | 高負載或超時 | 增加超時時間 |
| 數據庫快速增長 | 檢查頻繁 | 減少檢查頻率或清理舊數據 |

---

**最後更新**: 2026-02-23
**維護者**: SimpleEC OMS團隊
