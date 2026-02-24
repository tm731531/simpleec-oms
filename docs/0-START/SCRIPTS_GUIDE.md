# Scripts Guide — SimpleEC OMS 操作腳本

> 簡易電商訂單管理系統的各種腳本說明與用法

---

## 📍 目錄結構

```
simpleec-oms/
├── kafka-topic-manager.sh      ← Kafka 管理工具（常用）
├── stop-all.sh                 ← 停止所有容器（常用）
├── scripts/
│   ├── start-all.sh            ← 啟動所有服務（常用，日常使用）
│   ├── operations/
│   │   ├── start-on-boot.sh    ← 系統啟動時自動運行
│   │   ├── verify-kafka-setup.sh ← Kafka 設置驗證
│   │   └── create-test-data.sh ← 建立測試數據
│   └── tools/
│       ├── crud-tool.py        ← Python CRUD 工具
│       └── crud-tool.sh        ← Bash CRUD 工具
```

---

## 🚀 快速啟動

### 場景 1：日常啟動系統（最常用）

```bash
# 方式 1：使用 start-all.sh（推薦）
bash scripts/start-all.sh

# 方式 2：使用 Docker Compose
docker compose up -d --build
```

**包含內容**：
- ✅ 編譯所有 Gradle 模塊
- ✅ 啟動 31 個 Docker 容器
- ✅ 驗證 API 健康檢查
- ✅ 檢查 Kafka topics 初始化

---

### 場景 2：停止系統

```bash
bash stop-all.sh
```

**效果**：
- 停止所有 Docker 容器
- 保留數據卷完整

---

### 場景 3：Kafka 操作

```bash
# 查看 topics
bash kafka-topic-manager.sh list

# 建立新 topic
bash kafka-topic-manager.sh create-topic my-topic 3

# 查看 consumer groups
bash kafka-topic-manager.sh list-groups

# 刪除 topic（謹慎！）
bash kafka-topic-manager.sh delete-topic my-topic
```

**功能**：
- 列表/建立/刪除 Kafka topics
- 管理 consumer groups
- 檢查分區配置

---

## 🔧 系統操作

### 系統啟動時自動運行

```bash
# 用於 systemd service 或 cron @reboot
bash scripts/operations/start-on-boot.sh
```

**作用**：
- 自動啟動所有 SimpleEC OMS 服務
- 重建 Docker images（防止版本過舊）
- 適合部署在生產環境

**配置**：
```bash
# Systemd service
/etc/systemd/system/simpleec-oms.service
Type=simple
ExecStart=/home/tom/ONEEC/simpleec-oms/start-on-boot.sh

# Cron
@reboot sleep 10 && /home/tom/ONEEC/simpleec-oms/scripts/operations/start-on-boot.sh
```

---

### 驗證 Kafka 設置

```bash
bash scripts/operations/verify-kafka-setup.sh
```

**檢查內容**：
- ✅ Kafka broker 健康狀態
- ✅ 所有 22 個 topics 是否存在
- ✅ Consumer groups 初始化
- ✅ Partition 分配
- ✅ 複製因子設置

**使用場景**：
- 新系統部署後驗證
- Kafka 故障排查
- 升級後確認配置

---

### 建立測試數據

```bash
bash scripts/operations/create-test-data.sh
```

**建立**：
- ✅ 測試商家 (merchants)
- ✅ 測試平台 (platforms)
- ✅ 測試產品 (products)
- ✅ 測試訂單 (orders)
- ✅ 測試退貨 (refunds)

**場景**：
- 開發環境初始化
- 功能測試
- 性能測試

---

## 🛠️ 實用工具

### CRUD 工具 — Python 版本

```bash
bash scripts/tools/crud-tool.py [entity] [action] [options]
```

**支持的實體**：
- `merchant` — 商家管理
- `platform` — 平台管理
- `product` — 產品管理
- `order` — 訂單管理

**示例**：
```bash
# 建立商家
python scripts/tools/crud-tool.py merchant create --name "Test Shop"

# 查詢訂單
python scripts/tools/crud-tool.py order read --id 123

# 更新產品
python scripts/tools/crud-tool.py product update --id 456 --price 9999
```

---

### CRUD 工具 — Bash 版本

```bash
bash scripts/tools/crud-tool.sh [entity] [action]
```

**示例**：
```bash
# 查看現有商家
bash scripts/tools/crud-tool.sh merchant list

# 建立新訂單
bash scripts/tools/crud-tool.sh order create

# 檢查 API 連接
bash scripts/tools/crud-tool.sh health check
```

---

## 📋 常見問題與故障排查

### Q1: 啟動失敗

```bash
# 1. 檢查 Docker 狀態
docker ps

# 2. 驗證 Kafka
bash scripts/operations/verify-kafka-setup.sh

# 3. 查看 API 日誌
docker compose logs simpleec-api

# 4. 檢查磁盤空間
df -h
```

---

### Q2: Kafka 初始化問題

```bash
# 驗證 Kafka 設置
bash scripts/operations/verify-kafka-setup.sh

# 重新初始化 topics
docker compose restart kafka-init

# 檢查 topics 狀態
bash kafka-topic-manager.sh list
```

---

### Q3: 系統在啟動時不自動運行

```bash
# 確認 systemd 服務是否啟用
systemctl is-enabled simpleec-oms

# 啟用服務
sudo systemctl enable simpleec-oms

# 檢查 cron 配置
crontab -l | grep simpleec-oms
```

---

## 🔐 安全性注意事項

⚠️ **不要在生產環境執行**：
- `scripts/operations/create-test-data.sh` — 會生成測試數據
- `bash kafka-topic-manager.sh delete-topic` — 會刪除 topics

⚠️ **保護 Credentials**：
- 不要將 `.env` 檔案提交到 Git
- 使用環境變量或密鑰管理系統
- 定期輪換 API 密鑰

---

## 📚 相關文檔

| 文檔 | 用途 |
|------|------|
| [QUICK_COMMANDS.md](QUICK_COMMANDS.md) | 可複製貼上的常用命令 |
| [OPERATIONS_RUNBOOK.md](../6-OPERATIONS/OPERATIONS_RUNBOOK.md) | 完整運維手冊 |
| [DEPLOYMENT.md](../6-OPERATIONS/DEPLOYMENT.md) | 部署指南 |
| [DOCKER_GUIDE.md](../6-OPERATIONS/DOCKER_GUIDE.md) | Docker 使用指南 |

---

**最後更新**: 2026-02-24
**負責人**: Claude + Happy Engineering
