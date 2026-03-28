# 快速開始（30 分鐘完成建置）

本指南帶您從零開始，在本機完整運行 SimpleEC OMS 所有服務。

**前置需求：** 開始前請確認已符合 [prerequisites.md](prerequisites.md) 中的所有需求。

---

## 步驟 1 — 複製儲存庫

```bash
git clone git@github.com:tm731531/simpleec-oms.git
cd simpleec-oms

# 初始化子模組（user-app 前端是一個 git 子模組）
git submodule update --init --recursive
```

完成後，您應該可以看到 `user-app/` 目錄已包含原始碼檔案。

---

## 步驟 2 — 設定環境變數

```bash
cp .env.example .env
```

用編輯器開啟 `.env`，至少需要設定以下項目：

```dotenv
# 所有環境皆必填
DB_PASSWORD=<您的資料庫密碼>

# 正式環境必填 — 可用以下指令產生：openssl rand -base64 64
JWT_SECRET=<256 位元或更長的隨機字串>

# 更新為您實際的前端網域
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8084
```

`.env.example` 檔案包含所有可用的金鑰，並附有說明註解與安全的開發預設值。標有 `# CHANGE IN PRODUCTION` 的值，在部署到任何可公開存取的環境前必須替換。

> **安全性：** `.env` 已加入 `.gitignore`，絕對不能提交至版本控制。

---

## 步驟 3 — 編譯所有模組

```bash
./gradlew clean build -x test
```

此指令會編譯全部 11 個 Gradle 模組，並產生 Docker 打包所需的 JAR 檔案。
`-x test` 旗標會跳過測試階段（本專案目前有 0 個測試檔案，此旗標可避免測試基礎設施的依賴問題）。

**預期的最終輸出：**

```
BUILD SUCCESSFUL in Xm Xs
```

若編譯失敗，請參閱本頁底部的[疑難排解](#疑難排解)章節。

---

## 步驟 4 — 啟動所有服務

```bash
docker compose up -d --build
```

此指令會從剛編譯好的 JAR 建置 Docker image，並啟動全部 26 個容器。
首次執行時，Docker 還會拉取基礎 image（PostgreSQL、Redis、Kafka、Grafana 等），
依您的網路速度可能需要數分鐘。

指令返回後請**等待 2–3 分鐘**，服務會依照依賴順序初始化：
PostgreSQL 與 Redis 優先啟動，接著是 Kafka，最後才是 Spring Boot 應用程式。

---

## 步驟 5 — 驗證堆疊狀態

執行以下檢查，確認所有服務均已正常運行：

```bash
# 26 個容器都應顯示 "Up" 狀態
docker compose ps

# API 健康檢查 — 應回傳 {"status":"UP"} 或類似內容
curl http://localhost:8082/api/health
```

在瀏覽器中開啟以下網址：

| URL | 預期畫面 |
|---|---|
| http://localhost:8082/api/health | JSON 健康狀態回應 |
| http://localhost:8088 | Kafka UI — Topic 清單與 Consumer Group |
| http://localhost:3000 | Grafana 登入頁（預設：admin/admin） |
| http://localhost:8090 | 商家前端（user-app） |
| http://localhost:8089 | 管理後台（admin-app） |

---

## 步驟 6 — 首次登入

### 管理後台（admin-app）

```
POST http://localhost:8082/api/admin/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "<來自種子資料>"
}
```

### 商家入口（user-app）

```
POST http://localhost:8082/api/auth/login
Content-Type: application/json

{
  "email": "admin@a00000.com",
  "password": "pass123456"
}
```

種子資料建立了商家 `a00000`，電子郵件如上所示。管理員密碼設定在 `docker/init-db/` 下的資料庫初始化腳本中。

兩個端點均會回傳 JWT Token，後續請求請以如下方式帶入：

```
Authorization: Bearer <token>
```

---

## 開發工作流程：快速重新部署

修改程式碼後，不需要重啟整個堆疊。使用快速重新部署腳本，只需 30–60 秒即可重新建置並重啟指定服務：

```bash
# 重新建置並重啟單一服務
./quick-redeploy.sh simpleec-api

# 一次重新建置並重啟多個服務
./quick-redeploy.sh simpleec-channel-job simpleec-order-job

# 查看所有可用服務名稱
./quick-redeploy.sh --list
```

重新部署前請務必先執行 `./gradlew clean build -x test` 重新編譯。

---

## 疑難排解

### 應用程式日誌出現「Cannot connect to Kafka」

Kafka 初始化比其他服務慢。請額外等待 30 秒後再檢查：

```bash
docker compose logs simpleec-kafka | tail -20
```

在輸出中找到 `Kafka Server started`。若 Kafka 仍在啟動中，其他服務將在 Kafka 就緒後自動重試。

### 執行 `./gradlew clean build` 時顯示 BUILD FAILED

請確認使用的是 Java 17：

```bash
java -version
# 必須顯示：openjdk version "17.x.x"
```

若您安裝了多個 JDK 版本，請明確設定 `JAVA_HOME`：

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew clean build -x test
```

### 容器持續重啟

請查看容器日誌以找出根本原因：

```bash
docker compose logs <service-name> --tail=50
```

常見原因：
- **資料庫連線被拒**：PostgreSQL 尚未初始化完成。等待 30 秒後嘗試 `docker compose restart <service-name>`。
- **環境變數缺失**：`.env` 中某個變數為空或拼寫錯誤。請參閱 [environment.md](environment.md) 中的變數說明。
- **連接埠已被佔用**：另一個程序占用了相同的連接埠。執行 `lsof -i :<port>` 找出並停止該程序。

### 前端顯示空白頁面或 API 錯誤

請確認 `.env` 中的 CORS 設定正確：

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8084,http://localhost:8090,http://localhost:8089
```

然後重新部署 API 服務：

```bash
./quick-redeploy.sh simpleec-api
```

### 容器立即退出並顯示「out of memory」

在 Docker Desktop 設定中將記憶體配置增加至至少 12 GB，然後重啟堆疊：

```bash
docker compose down
docker compose up -d --build
```

---

## 下一步

- 閱讀[系統架構概覽](../02-architecture/overview.md)，了解各元件之間的互動方式。
- 查看[環境變數](environment.md)，取得所有設定選項的完整說明。
- 查看 [HANDLER_REGISTRY.md](../../3-EVENT-FLOW/HANDLER_REGISTRY.md)，了解可用的 TaskType 及其對應的 Handler。
- 查看 [CHANNEL_IMPLEMENTATION_GUIDE.md](../../7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)，學習如何新增一個電商平台的支援。
