# 健康監控系統 - 部署檢查清單

## 📋 預部署檢查 (部署前)

### 環境準備

- [ ] **操作系統** - 確認是Linux/Unix (推薦 Ubuntu 20.04+)
- [ ] **Docker** - 版本 ≥ 20.10 (`docker --version`)
- [ ] **Docker Compose** - 版本 ≥ 2.0 (`docker-compose --version`)
- [ ] **磁盤空間** - 至少 50GB 可用空間
- [ ] **內存** - 至少 16GB RAM
- [ ] **網路** - 確保可以訪問外網和相關平台API

### 依賴服務檢查

- [ ] **PostgreSQL 16** - 可訪問並運行 (`psql -U postgres -h localhost`)
- [ ] **Kafka 3.7.1** - 可訪問並運行 (`kafka-topics.sh --list`)
- [ ] **Redis 7** - 可訪問並運行 (`redis-cli ping`)
- [ ] **Nginx** - 配置已準備

### 配置檔案檢查

```bash
# 驗證所有必要的配置文件存在
ls -la /home/tom/ONEEC/simpleec-oms/config.yaml
ls -la /home/tom/ONEEC/simpleec-oms/docker-compose.yml
ls -la /home/tom/ONEEC/simpleec-oms/docker/nginx.conf
```

檢查清單:
- [ ] `config.yaml` 存在且包含7個平台配置
- [ ] `docker-compose.yml` 已從 `config.yaml` 生成
- [ ] Nginx配置文件存在
- [ ] 所有必要的環境變量已設置

### 數據庫準備

```bash
# 連接到PostgreSQL
docker exec -it simpleec-oms-postgres-1 psql -U postgres

# 驗證表結構
\dt  # 列出所有表
SELECT * FROM information_schema.tables WHERE table_name LIKE 'channel%';
```

檢查清單:
- [ ] `channels` 表存在，包含 `enable_sync` 字段
- [ ] `platforms` 表存在，包含 `actived` 字段
- [ ] `channel_sync_logs` 表存在，包含所有必要字段
- [ ] 初始數據已加載 (至少1個平台，1個商家)

---

## 🚀 部署步驟

### 步驟 1: 獲取最新代碼

```bash
cd /home/tom/ONEEC/simpleec-oms

# 確保在正確的分支
git branch -v

# 拉取最新代碼
git pull origin ops/production

# 驗證狀態
git status
```

檢查清單:
- [ ] 成功拉取代碼
- [ ] 沒有未提交的更改
- [ ] 在 `ops/production` 分支

### 步驟 2: 驗證編譯

```bash
# 編譯Scheduler Job
cd simpleec-scheduler-job
./mvnw clean compile
cd ..

# 編譯Channel Job
cd simpleec-channel-job
./mvnw clean compile
cd ..

# 編譯API
cd simpleec-oms-backend
./mvnw clean compile
cd ..
```

檢查清單:
- [ ] Scheduler Job 編譯成功
- [ ] Channel Job 編譯成功
- [ ] API 編譯成功
- [ ] 沒有編譯錯誤

### 步驟 3: 生成Docker鏡像

```bash
# 構建所有镜像
docker compose build

# 驗證镜像
docker images | grep simpleec
```

檢查清單:
- [ ] `scheduler-job` 鏡像已生成
- [ ] `channel-job` 鏡像已生成
- [ ] `api` 鏡像已生成
- [ ] 所有鏡像大小合理 (< 1GB 每個)

### 步驟 4: 啟動服務

```bash
# 關閉現有服務 (如果有)
docker compose down

# 啟動新服務
docker compose up -d

# 驗證服務狀態
docker compose ps

# 查看啟動日誌
docker logs -f simpleec-oms-scheduler-job-1
docker logs -f simpleec-oms-channel-job-shopee-fast-1
docker logs -f simpleec-oms-api-1
```

檢查清單:
- [ ] Scheduler Job 容器運行中
- [ ] Channel Job 容器運行中 (至少Shopee)
- [ ] API 容器運行中
- [ ] 沒有明顯的錯誤日誌

---

## ✅ 部署後驗證 (部署後立即)

### 驗證 1: 服務健康檢查

```bash
# 檢查所有服務狀態
docker compose ps

# 預期輸出: 所有容器都應該是 "Up" 狀態
```

檢查清單:
- [ ] 所有容器狀態為 "Up"
- [ ] 沒有容器重啟循環

### 驗證 2: 數據庫連接

```bash
# 進入API容器
docker exec -it simpleec-oms-api-1 bash

# 驗證數據庫連接
psql -h postgres -U simpleec_user -d simpleec_oms -c "SELECT COUNT(*) FROM channels;"

# 預期: 應該返回一個整數 (通路數量)
```

檢查清單:
- [ ] 數據庫連接成功
- [ ] 可以查詢通路數據

### 驗證 3: Kafka連接

```bash
# 檢查Kafka主題
docker exec simpleec-oms-kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --list

# 預期: 應該看到平台相關的主題 (shopee.fast, momo.fast 等)
```

檢查清單:
- [ ] 可以列出Kafka主題
- [ ] 所有7個平台的fast/slow主題都存在

### 驗證 4: 調度器執行

```bash
# 檢查調度器日誌 (前5分鐘內)
docker logs simpleec-oms-scheduler-job-1 --tail 50

# 預期: 應該看到類似的消息
# INFO: Running health check scheduler at 2026-02-23 11:30:00
# INFO: Publishing X channel health checks
# INFO: Publishing 7 platform health checks
```

檢查清單:
- [ ] 調度器在日誌中有執行記錄
- [ ] 沒有明顯的錯誤

### 驗證 5: REST API 測試

```bash
# 測試健康摘要端點
curl -X GET "http://localhost:8083/api/health/summary" \
  -H "Authorization: Bearer <valid_token>" \
  -H "Content-Type: application/json"

# 預期: 返回JSON格式的摘要
# {
#   "totalChecks": 0,
#   "recentChecks": 0,
#   "healthyCount": 0,
#   "unhealthyCount": 0,
#   "healthPercentage": 0.0
# }
```

檢查清單:
- [ ] API 返回200狀態碼
- [ ] 返回有效的JSON
- [ ] 沒有認證錯誤

### 驗證 6: 前端訪問

```bash
# 在瀏覽器中訪問
# http://localhost:8089/health
# 或
# https://oms.tomting.com/health (如果配置了Cloudflare)
```

檢查清單:
- [ ] 頁面成功加載
- [ ] 可以看到健康儀表板
- [ ] 沒有控制台錯誤 (F12開發者工具)

---

## 📊 功能驗證 (部署後24小時內)

### 驗證 7: 檢查記錄生成

```sql
-- 執行以下查詢
SELECT COUNT(*) as total_checks FROM channel_sync_logs;

-- 預期: 應該看到> 0的數字
-- 如果是0,可能調度器未執行或消息未被消費
```

檢查清單:
- [ ] 至少有1條檢查記錄
- [ ] 記錄的時間戳是最近的 (< 5分鐘)

### 驗證 8: 通路狀態檢查

```bash
# 查詢某個通路的狀態
curl -X GET "http://localhost:8083/api/health/channel/channel-001" \
  -H "Authorization: Bearer <valid_token>"

# 預期: 返回該通路的最近健康狀態
```

檢查清單:
- [ ] 返回有效的狀態 (healthy/unhealthy)
- [ ] 返回HTTP狀態碼

### 驗證 9: 平台狀態檢查

```bash
# 查詢平台狀態
curl -X GET "http://localhost:8083/api/health/platform/shopee" \
  -H "Authorization: Bearer <valid_token>"

# 預期: 返回該平台的狀態
```

檢查清單:
- [ ] 所有7個平台都可查詢
- [ ] 返回有效的狀態碼和健康狀態

### 驗證 10: 歷史記錄檢查

```bash
# 查詢通路歷史
curl -X GET "http://localhost:8083/api/health/channel/channel-001/history?page=0&size=5" \
  -H "Authorization: Bearer <valid_token>"

# 預期: 返回最多5條歷史記錄
```

檢查清單:
- [ ] 返回分頁結果
- [ ] 日期時間格式正確
- [ ] 包含HTTP狀態碼信息

---

## 🔒 安全檢查

### 檢查清單

- [ ] **API認證** - 所有API端點都需要有效的JWT令牌
  ```bash
  # 測試不帶令牌的請求,應該返回401
  curl -X GET "http://localhost:8083/api/health/summary"
  ```

- [ ] **HTTPS配置** - 生產環境應使用HTTPS
  ```bash
  # 驗證Nginx SSL配置
  docker exec simpleec-oms-nginx-1 nginx -T | grep ssl
  ```

- [ ] **數據庫密碼** - PostgreSQL使用強密碼
  ```bash
  # 確認環境變量中的密碼已更改
  docker inspect simpleec-oms-postgres-1 | grep POSTGRES_PASSWORD
  ```

- [ ] **API密鑰** - Platform API密鑰存儲安全
  ```bash
  # 確認令牌未在日誌中暴露
  docker logs simpleec-oms-api-1 | grep -i "token\|password"
  ```

---

## 📈 性能檢查

### 檢查清單

- [ ] **響應時間** - API響應時間 < 500ms
  ```bash
  time curl http://localhost:8083/api/health/summary
  ```

- [ ] **內存使用** - 每個服務 < 1GB
  ```bash
  docker stats --no-stream
  ```

- [ ] **CPU使用** - 閒置時 < 5%
  ```bash
  docker stats --no-stream
  ```

- [ ] **磁盤使用** - 數據庫 < 10GB
  ```bash
  docker exec simpleec-oms-postgres-1 du -sh /var/lib/postgresql/data
  ```

---

## 🔄 部署後監控 (持續)

### 每日檢查

- [ ] 檢查容器狀態: `docker compose ps`
- [ ] 檢查磁盤空間: `df -h`
- [ ] 檢查日誌中的錯誤: `docker logs simpleec-oms-scheduler-job-1 | grep ERROR`
- [ ] 驗證API可訪問性: `curl http://localhost:8083/api/health/summary`

### 每週檢查

- [ ] 查看健康百分比趨勢
- [ ] 確認沒有連續的失敗
- [ ] 驗證日誌輪轉正常工作
- [ ] 檢查數據庫大小增長

### 每月檢查

- [ ] 生成月度性能報告
- [ ] 清理舊日誌 (保留30天)
- [ ] 驗證備份是否正常
- [ ] 確認令牌有效期

---

## 🚨 回滾計劃 (如需回滾)

### 步驟 1: 停止新服務

```bash
docker compose down
```

### 步驟 2: 恢復前一個版本

```bash
git checkout <previous-commit-hash>
docker compose build
docker compose up -d
```

### 步驟 3: 驗證服務

```bash
docker logs -f simpleec-oms-scheduler-job-1
curl http://localhost:8083/api/health/summary
```

### 步驟 4: 數據恢復 (如需要)

```bash
# 從備份恢復數據庫
docker exec simpleec-oms-postgres-1 pg_restore \
  -U simpleec_user -d simpleec_oms \
  /backups/db_backup_2026-02-23.dump
```

---

## ✨ 部署成功標誌

部署被認為成功,當:

✅ 所有容器都在運行且沒有重啟循環
✅ 調度器每5分鐘執行一次
✅ 每5分鐘生成新的健康檢查記錄
✅ 所有平台狀態都是綠色 (healthy)
✅ 前端儀表板可以訪問並顯示數據
✅ 所有REST API端點都返回200狀態碼
✅ 沒有錯誤日誌

---

## 📞 部署支持

部署過程中遇到問題,請:

1. **查看日誌** - 檢查容器日誌了解具體錯誤
2. **運行診斷** - 使用故障排除指南進行診斷
3. **聯繫支持** - 提供以下信息:
   - 完整的錯誤日誌
   - 系統配置 (OS, Docker版本等)
   - 部署步驟和時間
   - 已嘗試的解決方案

---

**檢查清單版本**: 1.0
**最後更新**: 2026-02-23
**適用環境**: Docker Compose (開發/測試/生產)
