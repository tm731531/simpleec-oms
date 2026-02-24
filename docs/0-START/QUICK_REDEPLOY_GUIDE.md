# 快速重啟指南 - quick-redeploy.sh

## 概述

`quick-redeploy.sh` 是一個智能部署腳本，用於快速重啟受影響的 Docker 容器，**避免重啟整個系統**。

### 核心功能
✅ **只重啟受影響的服務** — 修改某個 JOB 後快速部署
✅ **自動分析依賴** — 根據 docker-compose.yml 自動確定哪些服務需要重啟
✅ **支持全量模式** — 如果需要完整重啟，使用 `--all` 參數
✅ **友好的交互** — 彩色輸出，清晰的進度提示

---

## 基本用法

### 快速重啟單個服務（推薦用於開發）

```bash
# 修改 simpleec-channel-job 代碼後
./quick-redeploy.sh simpleec-channel-job

# 修改 API 代碼後
./quick-redeploy.sh simpleec-api

# 修改 scheduler 代碼後
./quick-redeploy.sh simpleec-scheduler-job
```

腳本會：
1. 自動檢測該服務的依賴
2. 只重建這個服務的 Docker 鏡像
3. 停止舊容器，啟動新容器
4. 顯示部署摘要和日誌查看命令

**耗時**：30-60 秒（遠快於 `docker compose up -d --build`）

### 全量重啟（需要完整部署）

```bash
# 修改了基礎設施配置或需要完整重啟時
./quick-redeploy.sh --all
```

腳本會：
1. 完整重建所有 Docker 鏡像
2. 啟動所有服務
3. 系統初始化需要 2-3 分鐘

**耗時**：3-5 分鐘

---

## 常用命令

### 開發工作流

```bash
# 1. 修改代碼
# （編輯 Java 文件）

# 2. 本地編譯（跳過測試）
./gradlew clean build -x test

# 3. 快速重啟（關鍵步驟！）
./quick-redeploy.sh simpleec-channel-job

# 4. 查看日誌
docker logs simpleec-channel-job -f

# 5. 測試 API 或手動驗證
curl http://localhost:8082/api/health
```

### 服務信息查詢

```bash
# 列出所有可用的服務
./quick-redeploy.sh --list

# 查看某個服務的依賴
./quick-redeploy.sh --deps simpleec-channel-job

# 顯示幫助信息
./quick-redeploy.sh --help
```

---

## 實踐場景

### 場景 1：修改單個 Channel Job

```bash
# 修改 Cyberbiz 的 FETCH_ORDERS 邏輯
# 編輯：simpleec-channel-job/src/main/java/.../CyberbizOrderHandler.java

# 編譯
./gradlew clean build -x test

# 快速重啟（只影響 channel-job）
./quick-redeploy.sh simpleec-channel-job

# 查看日誌
docker logs simpleec-channel-cyberbiz-slow -f
```

✅ **優勢**：30-40 秒完成，其他 13 個 Channel Jobs 繼續運行

### 場景 2：修改 API 端點

```bash
# 修改：simpleec-api/src/main/java/.../HealthController.java

./gradlew clean build -x test
./quick-redeploy.sh simpleec-api

# 測試 API
curl http://localhost:8082/api/health
```

✅ **優勢**：只重啟 API，不中斷 Job 處理

### 場景 3：修改通用服務（simpleec-core）

```bash
# simpleec-core 是共用庫，許多服務都依賴它
# 修改後需要重新編譯所有依賴的模組

./gradlew clean build -x test

# 受影響的服務：所有 Channel Jobs + Order/Scheduler/Backend/Frontend Job + API
# 此時建議全量重啟
./quick-redeploy.sh --all

# 或者分別重啟主要服務（更細粒度）
./quick-redeploy.sh simpleec-channel-job
./quick-redeploy.sh simpleec-order-job
./quick-redeploy.sh simpleec-api
```

⚠️ **注意**：修改 core 依賴時，考慮是否需要 `--all`

### 場景 4：Kafka 或數據庫配置變化

```bash
# 如果修改了 docker-compose.yml 中的 Kafka 或 PostgreSQL 配置
# 則需要完整重啟

./quick-redeploy.sh --all
```

---

## 腳本輸出示例

### 快速重啟單個服務

```
╔════════════════════════════════════════╗
║  快速重啟服務: simpleec-channel-job
╚════════════════════════════════════════╝

此服務依賴：
  • postgres
  • redis
  • kafka

將要重啟的服務（共 4 個）：
  ► simpleec-channel-job
  ► postgres
  ► redis
  ► kafka

[1/3] 正在重建 Docker 映像...
✓ 映像重建完成

[2/3] 正在停止舊服務...
✓ 舊服務已停止

[3/3] 正在啟動新服務...
✓ 新服務已啟動

═══════════════════════════════════════
✓ 部署完成！

查看日誌：
  docker logs simpleec-channel-job -f

查看運行狀態：
  docker ps | grep simpleec
═══════════════════════════════════════
```

---

## 常見問題

### Q：為什麼只重啟某個服務比全量重啟快？

A：因為：
- 只重建一個 Docker 鏡像（30-40 秒）vs 重建所有鏡像（3-5 分鐘）
- 其他服務保持運行，無需冷啟動
- 減少資料庫連接、Kafka 重新連接的開銷

### Q：我修改的是共用代碼（simpleec-core），怎麼辦？

A：
1. 如果只想快速測試：逐個重啟主要服務
   ```bash
   ./quick-redeploy.sh simpleec-channel-job
   ./quick-redeploy.sh simpleec-order-job
   ./quick-redeploy.sh simpleec-api
   ```

2. 如果想確保全部同步：使用全量模式
   ```bash
   ./quick-redeploy.sh --all
   ```

### Q：怎麼知道某個服務依賴哪些基礎設施？

A：使用 `--deps` 參數查看
```bash
./quick-redeploy.sh --deps simpleec-scheduler-job
```

### Q：是否可以同時重啟多個服務？

A：目前腳本一次只支持一個服務。如果需要多個，可以：
```bash
./quick-redeploy.sh simpleec-channel-job
./quick-redeploy.sh simpleec-order-job
```

或使用 `--all` 進行全量重啟。

### Q：如果部署失敗了怎麼辦？

A：
1. 查看錯誤日誌
   ```bash
   docker logs <service-name>
   ```

2. 檢查代碼編譯
   ```bash
   ./gradlew build -x test
   ```

3. 嘗試完整重啟
   ```bash
   ./quick-redeploy.sh --all
   ```

### Q：舊的 `docker compose up -d --build` 命令還能用嗎？

A：可以，但：
- `docker compose up -d --build` 會重啟所有服務（5 分鐘）
- `./quick-redeploy.sh <service>` 只重啟需要的服務（30-60 秒）

推薦在開發時使用 `quick-redeploy.sh`，在生產部署時使用 `docker compose`。

---

## 環境變量

無需設置，腳本自動讀取：
- `docker-compose.yml` 配置
- Docker daemon 狀態

---

## 故障排除

### 腳本找不到 docker-compose.yml

```bash
# 確保在項目根目錄運行
cd /home/tom/ONEEC/simpleec-oms
./quick-redeploy.sh <service>
```

### Docker 命令不存在

```bash
# 確保 Docker daemon 正在運行
docker ps

# 如果找不到 docker compose，使用舊版命令
docker-compose --version
```

### 某個容器啟動失敗

```bash
# 查看詳細日誌
docker logs <container-name>

# 檢查依賴服務是否就緒
docker ps | grep simpleec

# 嘗試手動重啟
docker compose up -d <service-name>
```

---

## 與 QUICK_COMMANDS.md 的區別

| 腳本 | 用途 | 速度 | 用於 |
|------|------|------|------|
| **quick-redeploy.sh** | 快速重啟單個服務 | 30-60 秒 | 開發迭代 |
| **docker compose up -d --build** | 完整重啟所有服務 | 3-5 分鐘 | 生產部署 |
| **QUICK_COMMANDS.md** | 常用命令參考 | - | 快速查詢 |

---

## 更新日誌

### v1.0 (2026-02-24)
- ✅ 初版發布
- ✅ 支持快速重啟單個服務
- ✅ 自動依賴分析
- ✅ 全量模式支持
- ✅ 友好的彩色輸出

---

## 後續改進

計劃中的功能（歡迎提建議）：
- [ ] 支持同時重啟多個服務
- [ ] 服務健康檢查（等待容器就緒）
- [ ] 自動日誌追蹤（啟動後自動 tail logs）
- [ ] 回滾功能（快速恢復到上一個版本）
- [ ] 性能統計（顯示重啟耗時）
