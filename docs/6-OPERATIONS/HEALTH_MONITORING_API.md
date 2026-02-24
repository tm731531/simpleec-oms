# Health Monitoring API Reference

## 基本信息

**基礎 URL**: `http://localhost:8083/api/health`

**認證**: JWT Bearer Token (通過用戶登錄獲取)

**Content-Type**: `application/json`

**Response Format**: 所有成功響應返回JSON

---

## API端點概覽

| 方法 | 端點 | 說明 |
|------|------|------|
| GET | `/api/health/channel/{channelId}` | 查詢通路健康狀態 |
| GET | `/api/health/platform/{platformCode}` | 查詢平台健康狀態 |
| GET | `/api/health/channel/{channelId}/history` | 查詢通路檢查歷史 |
| GET | `/api/health/platform/{platformCode}/history` | 查詢平台檢查歷史 |
| GET | `/api/health/summary` | 獲取健康摘要 |

---

## 詳細API文檔

### 1. 查詢通路健康狀態

#### 請求

```http
GET /api/health/channel/{channelId}
Authorization: Bearer {token}
```

**參數**:
- `channelId` (路徑) - 必需 - 通路ID，例如 `channel-001`

**示例**:
```bash
curl -X GET "http://localhost:8083/api/health/channel/channel-001" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

#### 響應

**狀態碼**: 200 OK

```json
{
  "httpStatus": 200,
  "health": "healthy",
  "errorMessage": null
}
```

**字段說明**:
| 字段 | 類型 | 說明 |
|------|------|------|
| httpStatus | number | HTTP狀態碼 (200, 401, 403, 500等) |
| health | string | "healthy" 或 "unhealthy" |
| errorMessage | string \| null | 錯誤消息，成功時為null |

**响應示例 - 失敗情況**:
```json
{
  "httpStatus": 401,
  "health": "unhealthy",
  "errorMessage": "Token invalid or expired"
}
```

---

### 2. 查詢平台健康狀態

#### 請求

```http
GET /api/health/platform/{platformCode}
Authorization: Bearer {token}
```

**參數**:
- `platformCode` (路徑) - 必需 - 平台代碼: shopee, momo, yahoo, pchome, cyberbiz, shopline, shopify

**示例**:
```bash
curl -X GET "http://localhost:8083/api/health/platform/shopee" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

#### 響應

**狀態碼**: 200 OK

```json
{
  "httpStatus": 200,
  "health": "healthy",
  "errorMessage": null
}
```

---

### 3. 查詢通路檢查歷史

#### 請求

```http
GET /api/health/channel/{channelId}/history?page=0&size=10
Authorization: Bearer {token}
```

**參數**:
- `channelId` (路徑) - 必需 - 通路ID
- `page` (查詢) - 可選 - 頁碼，從0開始 (默認: 0)
- `size` (查詢) - 可選 - 每頁記錄數 (默認: 10)

**示例**:
```bash
curl -X GET "http://localhost:8083/api/health/channel/channel-001/history?page=0&size=20" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

#### 響應

**狀態碼**: 200 OK

```json
{
  "channelId": "channel-001",
  "totalRecords": 150,
  "page": 0,
  "size": 10,
  "logs": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "channelId": "channel-001",
      "merchantId": "merchant-001",
      "httpStatus": 200,
      "status": "success",
      "errorMessage": null,
      "createdAt": "2026-02-23T11:30:00"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "channelId": "channel-001",
      "merchantId": "merchant-001",
      "httpStatus": 401,
      "status": "failed",
      "errorMessage": "Token invalid or expired",
      "createdAt": "2026-02-23T11:25:00"
    }
  ]
}
```

**字段說明**:
| 字段 | 類型 | 說明 |
|------|------|------|
| channelId | string | 通路ID |
| totalRecords | number | 該通路的總檢查記錄數 |
| page | number | 當前頁碼 |
| size | number | 本頁記錄數 |
| logs | array | 檢查日誌數組 |
| logs[].id | string | 日誌ID (UUID) |
| logs[].channelId | string | 通路ID |
| logs[].merchantId | string | 商家ID |
| logs[].httpStatus | number | HTTP狀態碼 |
| logs[].status | string | "success" 或 "failed" |
| logs[].errorMessage | string \| null | 錯誤信息 |
| logs[].createdAt | string | ISO 8601格式時間戳 |

**分頁說明**:
```
第一頁 (page=0):   記錄 0-9
第二頁 (page=1):   記錄 10-19
第三頁 (page=2):   記錄 20-29
```

---

### 4. 查詢平台檢查歷史

#### 請求

```http
GET /api/health/platform/{platformCode}/history?page=0&size=10
Authorization: Bearer {token}
```

**參數**:
- `platformCode` (路徑) - 必需 - 平台代碼
- `page` (查詢) - 可選 - 頁碼 (默認: 0)
- `size` (查詢) - 可選 - 每頁記錄數 (默認: 10)

**示例**:
```bash
curl -X GET "http://localhost:8083/api/health/platform/shopee/history?page=0&size=20" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

#### 響應

**狀態碼**: 200 OK

```json
{
  "platformCode": "shopee",
  "totalRecords": 288,
  "page": 0,
  "size": 10,
  "logs": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440100",
      "channelId": null,
      "merchantId": null,
      "httpStatus": 200,
      "status": "success",
      "errorMessage": null,
      "createdAt": "2026-02-23T11:30:00"
    },
    ...
  ]
}
```

**注意**: 平台級日誌的 `channelId` 和 `merchantId` 始終為 `null`

---

### 5. 獲取健康摘要

#### 請求

```http
GET /api/health/summary
Authorization: Bearer {token}
```

**參數**: 無

**示例**:
```bash
curl -X GET "http://localhost:8083/api/health/summary" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

#### 響應

**狀態碼**: 200 OK

```json
{
  "totalChecks": 5000,
  "recentChecks": 100,
  "healthyCount": 95,
  "unhealthyCount": 5,
  "healthPercentage": 95.0
}
```

**字段說明**:
| 字段 | 類型 | 說明 |
|------|------|------|
| totalChecks | number | 系統總檢查次數 (自啟動以來) |
| recentChecks | number | 最近記錄的檢查次數 (通常為100) |
| healthyCount | number | 最近檢查中成功的次數 |
| unhealthyCount | number | 最近檢查中失敗的次數 |
| healthPercentage | number | 健康百分比 (0-100) |

**解釋示例**:
```
totalChecks: 5000          → 系統啟動至今已進行5000次檢查
recentChecks: 100          → 樣本基於最近100次檢查
healthyCount: 95           → 95次成功
unhealthyCount: 5          → 5次失敗
healthPercentage: 95.0     → 成功率為95%
```

---

## 錯誤響應

### 401 Unauthorized

當未提供有效的認證令牌時:

```json
{
  "error": "Unauthorized",
  "message": "No valid authentication token provided"
}
```

**解決**: 登錄並獲取有效的JWT令牌

### 404 Not Found

當資源不存在時:

```json
{
  "httpStatus": 404,
  "health": "unhealthy",
  "errorMessage": "Channel not found"
}
```

**原因**: 通路ID不存在或已被刪除

### 500 Internal Server Error

服務器內部錯誤:

```json
{
  "error": "Internal Server Error",
  "message": "An unexpected error occurred"
}
```

---

## 使用示例

### Python 示例

```python
import requests
from datetime import datetime

class HealthCheckClient:
    def __init__(self, base_url, token):
        self.base_url = base_url
        self.headers = {"Authorization": f"Bearer {token}"}

    def get_channel_health(self, channel_id):
        """查詢通路健康狀態"""
        url = f"{self.base_url}/channel/{channel_id}"
        response = requests.get(url, headers=self.headers)
        return response.json()

    def get_channel_history(self, channel_id, page=0, size=10):
        """查詢通路檢查歷史"""
        url = f"{self.base_url}/channel/{channel_id}/history"
        params = {"page": page, "size": size}
        response = requests.get(url, headers=self.headers, params=params)
        return response.json()

    def get_health_summary(self):
        """獲取健康摘要"""
        url = f"{self.base_url}/summary"
        response = requests.get(url, headers=self.headers)
        return response.json()

# 使用示例
client = HealthCheckClient("http://localhost:8083/api/health", "your_token")

# 查詢通路狀態
status = client.get_channel_health("channel-001")
print(f"Channel health: {status['health']} (HTTP {status['httpStatus']})")

# 查詢歷史
history = client.get_channel_history("channel-001", page=0, size=20)
print(f"Total records: {history['totalRecords']}")
for log in history['logs']:
    print(f"  {log['createdAt']}: {log['status']} (HTTP {log['httpStatus']})")

# 查詢摘要
summary = client.get_health_summary()
print(f"Health percentage: {summary['healthPercentage']}%")
```

### JavaScript/TypeScript 示例

```typescript
class HealthCheckClient {
  constructor(private baseUrl: string, private token: string) {}

  async getChannelHealth(channelId: string) {
    const response = await fetch(
      `${this.baseUrl}/channel/${channelId}`,
      { headers: { Authorization: `Bearer ${this.token}` } }
    );
    return response.json();
  }

  async getChannelHistory(
    channelId: string,
    page: number = 0,
    size: number = 10
  ) {
    const response = await fetch(
      `${this.baseUrl}/channel/${channelId}/history?page=${page}&size=${size}`,
      { headers: { Authorization: `Bearer ${this.token}` } }
    );
    return response.json();
  }

  async getHealthSummary() {
    const response = await fetch(`${this.baseUrl}/summary`, {
      headers: { Authorization: `Bearer ${this.token}` },
    });
    return response.json();
  }
}

// 使用示例
const client = new HealthCheckClient(
  "http://localhost:8083/api/health",
  "your_token"
);

const status = await client.getChannelHealth("channel-001");
console.log(`Health: ${status.health} (HTTP ${status.httpStatus})`);

const summary = await client.getHealthSummary();
console.log(`Overall health: ${summary.healthPercentage}%`);
```

---

## 速率限制

目前沒有實施硬性速率限制，但建議:
- **最大並發請求**: 10個/秒
- **最大請求數**: 100個/分鐘

超過限制時系統可能返回 429 Too Many Requests

---

## 時間格式

所有時間戳使用 **ISO 8601** 格式:
```
2026-02-23T11:30:00
```

- 時區: UTC (Z後綴表示)
- 精度: 秒級
- 示例: `2026-02-23T11:30:00Z`

---

## 最佳實踐

### 1. 錯誤處理

```python
try:
    status = client.get_channel_health(channel_id)
    if status['health'] == 'unhealthy':
        print(f"Alert: {status['errorMessage']}")
except requests.exceptions.RequestException as e:
    print(f"API call failed: {e}")
```

### 2. 分頁查詢

當需要獲取大量歷史數據時使用分頁:

```python
page = 0
all_logs = []
while True:
    result = client.get_channel_history(channel_id, page=page, size=50)
    all_logs.extend(result['logs'])
    if len(result['logs']) < 50:
        break
    page += 1
```

### 3. 緩存策略

健康狀態變化不頻繁，可以實施簡單緩存:

```python
from datetime import datetime, timedelta

class CachedHealthCheckClient(HealthCheckClient):
    def __init__(self, *args, cache_ttl_seconds=60, **kwargs):
        super().__init__(*args, **kwargs)
        self.cache = {}
        self.cache_ttl = cache_ttl_seconds

    def get_channel_health(self, channel_id):
        cache_key = f"channel:{channel_id}"
        if cache_key in self.cache:
            data, timestamp = self.cache[cache_key]
            if datetime.now() - timestamp < timedelta(seconds=self.cache_ttl):
                return data

        data = super().get_channel_health(channel_id)
        self.cache[cache_key] = (data, datetime.now())
        return data
```

---

**API版本**: 1.0
**最後更新**: 2026-02-23
**維護者**: SimpleEC OMS 開發團隊
