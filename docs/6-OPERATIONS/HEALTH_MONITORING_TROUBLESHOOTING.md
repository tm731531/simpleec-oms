# 健康監控系統 - 故障排除指南

## 🔧 快速診斷流程

```
問題出現
    ↓
[步驟1] 確認問題類型 → 查看相應部分
    ↓
[步驟2] 按照解決方案排查
    ↓
[步驟3] 檢查日誌 → 查看日誌位置部分
    ↓
[步驟4] 如仍未解決 → 聯繫技術支持
```

---

## 問題分類與解決方案

### 類別A: 健康檢查不運作

#### A1. 「沒有任何檢查記錄」

**症狀**:
- 儀表板中「總檢查數」為0
- 沒有任何檢查日誌

**診斷**:
```bash
# 1. 檢查調度器服務是否運行
docker ps | grep scheduler-job

# 2. 檢查服務日誌
docker logs simpleec-oms-scheduler-job-1 | tail -100

# 3. 檢查Kafka連接
docker exec simpleec-oms-kafka-1 kafka-topics.sh --list
```

**解決方案**:

| 原因 | 檢查方法 | 解決步驟 |
|------|---------|---------|
| Scheduler服務未啟動 | `docker ps` 中無scheduler-job | 執行 `docker compose up -d` |
| Kafka不可用 | Kafka服務異常 | 檢查Kafka磁盤空間，重啟broker |
| 數據庫連接失敗 | 日誌有連接錯誤 | 檢查PostgreSQL連接字符串 |
| 未有啟用的通路 | 查詢: SELECT COUNT(*) FROM channels WHERE enable_sync=true | 添加啟用的通路 |

**詳細檢查清單**:
```bash
# 檢查調度器日誌中是否有執行記錄
docker logs simpleec-oms-scheduler-job-1 | grep "HealthCheckScheduler" | head -20

# 預期日誌:
# 2026-02-23 11:30:00 INFO: Running health check scheduler
# 2026-02-23 11:30:00 INFO: Publishing 5 channel health checks
# 2026-02-23 11:30:00 INFO: Publishing 7 platform health checks
```

---

#### A2. 「只有部分通路有檢查記錄」

**症狀**:
- 某些通路有記錄，某些沒有
- 某個平台的記錄比其他平台少

**診斷**:
```sql
-- 檢查啟用的通路
SELECT id, merchant_id, platform_code, enable_sync FROM channels;

-- 檢查活躍的平台
SELECT code, name, actived FROM platforms;

-- 檢查哪些通路有記錄
SELECT DISTINCT channel_id FROM channel_sync_logs WHERE channel_id IS NOT NULL;
```

**解決方案**:

| 原因 | 檢查方法 | 解決步驟 |
|------|---------|---------|
| 通路未啟用 | enable_sync = false | 更新通路: UPDATE channels SET enable_sync=true WHERE id='...' |
| 平台未活躍 | actived = false | 更新平台: UPDATE platforms SET actived=true WHERE code='...' |
| 通路未配置令牌 | token 為 NULL | 在後台管理添加令牌 |
| Channel-Job未消費消息 | 消息在Kafka中堆積 | 檢查Channel-Job是否運行 |

---

### 類別B: 所有通路都顯示異常

#### B1. 「所有檢查都返回401」

**症狀**:
- 所有平台狀態都是紅色
- HTTP狀態碼都是401
- 錯誤信息: "Token invalid or expired"

**原因分析**:
最可能是: **所有的API令牌同時過期**

**診斷步驟**:
```bash
# 1. 檢查最近的日誌時間
docker logs simpleec-oms-channel-job-shopee-fast-1 | tail -50 | grep "401"

# 2. 檢查數據庫中的令牌配置
SELECT id, merchant_id, token, created_at FROM channels LIMIT 5;

# 3. 檢查Kafka消息是否有發送
docker exec simpleec-oms-kafka-1 kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group check-health \
  --describe
```

**解決方案**:
```
Step 1: 登錄後台管理系統
Step 2: 進入「商家管理」或「通路管理」
Step 3: 批量重新授權所有通路
Step 4: 重新生成所有API令牌
Step 5: 等待5分鐘，下一次調度自動觸發檢查
Step 6: 驗證狀態是否恢復為綠色
```

**預防措施**:
- 設置令牌過期提醒 (90天前通知)
- 建立令牌更新日程表
- 為重要平台設置多個令牌備份

---

#### B2. 「所有檢查都返回403」

**症狀**:
- 所有平台都是紅色
- HTTP狀態碼都是403
- 錯誤信息: "Insufficient permissions"

**原因分析**:
最可能是: **API應用權限被撤銷或商家賬戶被禁用**

**診斷**:
```bash
# 檢查權限配置
SELECT id, merchant_id, platform_code, token FROM channels WHERE enable_sync=true;

# 檢查商家狀態
SELECT id, merchant_id, status FROM channels WHERE enable_sync=true;
```

**解決方案**:
```
Step 1: 檢查商家賬戶狀態
      - 登入平台官方商家後台
      - 確認賬戶是否被禁用/凍結/扣費
Step 2: 驗證API應用權限
      - 進入平台開發者中心
      - 確認應用已授予所有必要權限
Step 3: 檢查商家訂閱方案
      - 某些API可能需要高級方案
      - 確認商家訂閱了必要功能
Step 4: 聯繫平台支持
      - 提供商家ID和應用ID
      - 請求人工檢查權限配置
```

---

#### B3. 「所有檢查都返回500」

**症狀**:
- 所有平台都顯示紅色
- HTTP狀態碼都是500
- 錯誤信息: "Platform service error"

**原因分析**:
最可能是: **平台服務故障或OMS系統問題**

**診斷**:
```bash
# 1. 檢查OMS到平台的網路連接
docker exec simpleec-oms-channel-job-shopee-fast-1 ping api.shopee.com

# 2. 檢查DNS解析
docker exec simpleec-oms-channel-job-shopee-fast-1 nslookup api.shopee.com

# 3. 檢查Channel-Job日誌中的具體錯誤
docker logs simpleec-oms-channel-job-shopee-fast-1 | grep -i "error\|exception" | tail -20

# 4. 檢查平台官方狀態頁
# 訪問: https://status.shopee.com 等平台狀態頁
```

**解決方案**:
```
如果是平台故障:
  - 檢查平台官方狀態頁
  - 查看是否有維護通告
  - 等待平台恢復(通常15-60分鐘)
  - 必要時聯繫平台支持

如果是網路問題:
  - 檢查防火牆規則
  - 確認OMS服務器可以訪問外網
  - 檢查DNS配置
  - 測試到平台的連接: curl https://api.shopee.com/health

如果是超時:
  - 增加RestTemplate超時設置
  - 修改 RestTemplateConfig.java
  - 改為: setReadTimeout(Duration.ofSeconds(15))
  - 重新部署
```

---

### 類別C: 特定通路異常

#### C1. 「某個通路一直返回相同的錯誤」

**症狀**:
- 某個特定通路總是紅色
- 其他通路正常

**診斷**:
```sql
-- 查看該通路最近的檢查記錄
SELECT http_status, error_message, created_at
FROM channel_sync_logs
WHERE channel_id = 'channel-xxx'
ORDER BY created_at DESC
LIMIT 20;

-- 檢查該通路的配置
SELECT * FROM channels WHERE id = 'channel-xxx';
```

**基於HTTP狀態碼的解決方案**:

**如果是401**:
```
step 1: 確認該通路的令牌
Step 2: 重新授權該商家
Step 3: 更新令牌
Step 4: 驗證
```

**如果是403**:
```
Step 1: 檢查該商家的賬戶狀態
Step 2: 確認API權限配置
Step 3: 確認商家訂閱方案
Step 4: 必要時聯繫平台
```

**如果是404**:
```
Step 1: 確認該平台的API端點是否更新
Step 2: 查閱最新的API文檔
Step 3: 如果API已遷移，更新OMS配置
Step 4: 重新部署
```

---

#### C2. 「某個通路檢查間歇性失敗」

**症狀**:
- 通路狀態忽紅忽綠
- 某些檢查成功，某些失敗
- 沒有明顯的時間規律

**原因分析**:
最可能是: **網路不穩定、平台高負載、或超時**

**診斷**:
```bash
# 分析失敗率
SELECT
  DATE_TRUNC('hour', created_at) as hour,
  COUNT(*) as total,
  SUM(CASE WHEN http_status < 400 THEN 1 ELSE 0 END) as success,
  ROUND(100.0 * SUM(CASE WHEN http_status < 400 THEN 1 ELSE 0 END) / COUNT(*), 2) as success_rate
FROM channel_sync_logs
WHERE channel_id = 'channel-xxx'
GROUP BY DATE_TRUNC('hour', created_at)
ORDER BY hour DESC
LIMIT 24;

# 查看失敗模式
SELECT http_status, COUNT(*) as count
FROM channel_sync_logs
WHERE channel_id = 'channel-xxx' AND http_status >= 400
GROUP BY http_status;
```

**解決方案**:

| 現象 | 原因 | 解決方案 |
|------|------|---------|
| 成功率50-80% | 網路不穩定 | 檢查網路質量，可能需要改進ISP連接 |
| 每隔一定時間失敗 | 平台有定期維護或高峰 | 調整檢查時間避免高峰 |
| 隨機失敗 | 超時設置太短 | 增加超時時間: setReadTimeout(20s) |
| 特定時段多失敗 | 該商家的API額度限制 | 聯繫平台申請更高限額 |

---

### 類別D: 前端顯示問題

#### D1. 「健康儀表板載入失敗」

**症狀**:
- 訪問 `/health` 頁面無法加載
- 頁面空白或顯示錯誤

**診斷**:
```bash
# 1. 檢查前端應用是否運行
docker ps | grep user-app

# 2. 檢查前端應用日誌
docker logs simpleec-oms-user-app-1 | tail -50

# 3. 檢查API是否可以訪問
curl http://localhost:8083/api/health/summary

# 4. 開啟瀏覽器開發者工具
# 按 F12 → Console 標籤 → 查看錯誤消息
```

**解決方案**:

| 錯誤信息 | 原因 | 解決方案 |
|---------|------|---------|
| 404 Not Found | API路由不存在 | 確保API服務已啟動並部署了HealthCheckController |
| 401 Unauthorized | 認證令牌無效 | 重新登錄 |
| CORS Error | 跨域請求被阻止 | 檢查Nginx配置中的CORS設置 |
| Network Error | API服務不可達 | 檢查API服務是否運行，檢查防火牆 |

**詳細檢查**:
```bash
# 驗證API可訪問性
curl -X GET "http://localhost:8083/api/health/summary" \
  -H "Authorization: Bearer <your_token>"

# 如果返回JSON，API正常
# 如果返回404，controller未部署
# 如果返回401，令牌無效
```

---

#### D2. 「數據未更新」

**症狀**:
- 儀表板數據很久不變
- 刷新頁面後仍是舊數據

**原因分析**:
最可能是: **API未返回新數據，或調度器未執行**

**診斷**:
```bash
# 1. 檢查最新的檢查記錄時間
SELECT MAX(created_at) FROM channel_sync_logs;

# 2. 比較當前時間
# 如果差異 > 5分鐘，調度器可能未執行

# 3. 檢查調度器日誌
docker logs simpleec-oms-scheduler-job-1 | tail -50
```

**解決方案**:
```
如果調度器未執行:
  - 檢查服務是否運行: docker ps | grep scheduler
  - 重啟服務: docker restart simpleec-oms-scheduler-job-1
  - 查看日誌中的錯誤

如果調度器執行但未產生記錄:
  - 檢查是否有啟用的通路
  - 檢查Kafka連接
  - 檢查Channel-Job是否消費消息
```

---

## 日誌位置

### 主要服務日誌

| 服務 | 日誌位置 | 查看命令 |
|------|---------|---------|
| Scheduler Job | Docker容器 | `docker logs simpleec-oms-scheduler-job-1` |
| Channel Job (Shopee Fast) | Docker容器 | `docker logs simpleec-oms-channel-job-shopee-fast-1` |
| Channel Job (其他) | Docker容器 | `docker logs simpleec-oms-channel-job-{platform}-{speed}-1` |
| API | Docker容器 | `docker logs simpleec-oms-api-1` |
| Nginx | Docker容器 | `docker logs simpleec-oms-nginx-1` |

### 查看日誌的實用命令

```bash
# 查看最近100行
docker logs simpleec-oms-scheduler-job-1 --tail 100

# 實時監控日誌
docker logs simpleec-oms-scheduler-job-1 -f

# 查看特定時間段的日誌
docker logs simpleec-oms-scheduler-job-1 --since "2026-02-23T10:00:00" --until "2026-02-23T12:00:00"

# 查看包含特定關鍵字的日誌
docker logs simpleec-oms-scheduler-job-1 | grep "ERROR"
docker logs simpleec-oms-scheduler-job-1 | grep "healthCheck"
```

---

## 性能診斷

### Q: 檢查變得很慢

**診斷步驟**:
```bash
# 1. 檢查數據庫大小
SELECT COUNT(*) FROM channel_sync_logs;

# 2. 檢查查詢性能
EXPLAIN ANALYZE SELECT * FROM channel_sync_logs
WHERE channel_id = 'xxx' ORDER BY created_at DESC LIMIT 10;

# 3. 檢查索引
SELECT * FROM pg_stat_user_indexes WHERE relname = 'channel_sync_logs';
```

**解決方案**:
```sql
-- 如果查詢變慢，可能需要清理老數據
DELETE FROM channel_sync_logs
WHERE created_at < NOW() - INTERVAL '30 days';

-- 重新分析表
VACUUM ANALYZE channel_sync_logs;

-- 確保有適當的索引
CREATE INDEX IF NOT EXISTS idx_channel_sync_logs_channel_id
ON channel_sync_logs(channel_id);

CREATE INDEX IF NOT EXISTS idx_channel_sync_logs_created_at
ON channel_sync_logs(created_at DESC);
```

---

## 常見問題速查表

| 問題 | 快速檢查 | 最可能原因 |
|------|---------|---------|
| 沒有記錄 | `docker ps` 中無scheduler | Scheduler未啟動 |
| 所有401 | 數據庫檢查令牌 | 令牌同時過期 |
| 所有403 | 檢查商家賬戶 | 商家權限被撤銷 |
| 所有500 | 檢查網路和日誌 | 平台故障或網路問題 |
| 某個通路異常 | 檢查該通路配置 | 該通路配置問題 |
| 間歇性失敗 | 分析失敗率趨勢 | 網路不穩定或超時 |
| 前端無法加載 | 瀏覽器開發者工具 | API不可達或令牌無效 |
| 數據不更新 | 檢查最新記錄時間 | 調度器未執行 |

---

## 升級支持

如遇到上述解決方案無法解決的問題，請聯繫技術支持並提供:

1. **問題描述** - 詳細的症狀說明
2. **錯誤日誌** - 相關服務的日誌片段
3. **數據庫查詢結果** - 相關表的配置和記錄
4. **系統信息** - Docker版本、操作系統等
5. **時間信息** - 問題發生的確切時間

---

**最後更新**: 2026-02-23
**版本**: 1.0
