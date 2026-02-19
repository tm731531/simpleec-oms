# SimpleEC OMS Documentation Branch

這是 SimpleEC OMS 專案的純文檔分支（docs-only），不包含任何程式碼。

## 分支說明

本分支僅保留：
- 📚 所有文檔（.md 檔案）
- 🐳 Docker 相關設定檔（Dockerfile, docker-compose.yml）
- 📊 監控設定（Grafana, Prometheus, Loki, Tempo）
- 🔧 環境設定範例（.env.example）

## 文檔結構

```
.
├── docs/                       # 主要文檔目錄
│   ├── ABSTRACT_DESIGN.md     # 抽象設計
│   ├── EVENT_SAMPLES.md       # 事件流範例
│   ├── IMPLEMENTATION_PLAN.md # 實作計畫
│   ├── OPERATIONS_RUNBOOK.md  # 維運手冊
│   ├── STATUS.md              # 專案狀態
│   ├── event-flows/           # 事件流程文檔
│   └── plans/                 # 計畫文檔
├── docker/                    # Docker 設定
│   ├── Dockerfile.*          # 各服務的 Dockerfile
│   ├── grafana/              # Grafana 設定
│   ├── prometheus/           # Prometheus 設定
│   ├── loki/                 # Loki 設定
│   └── otel/                 # OpenTelemetry 設定
├── DESIGN.md                 # 系統設計文檔
├── DESIGN_v2.md              # 系統設計 v2
├── REWRITE_PLAN.md           # 重構計畫
└── docker-compose.yml        # Docker Compose 設定
```

## 主要文檔

### 架構設計
- [ABSTRACT_DESIGN.md](docs/ABSTRACT_DESIGN.md) - 系統抽象設計
- [DESIGN.md](DESIGN.md) - 詳細系統設計
- [DESIGN_v2.md](DESIGN_v2.md) - 第二版設計

### 事件流
- [EVENT_SAMPLES.md](docs/EVENT_SAMPLES.md) - 所有 Kafka topic 訊息範例（header/body 結構）
- [event-flows/](docs/event-flows/) - 各種事件流程詳細說明

### 實作與維運
- [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) - 實作計畫
- [OPERATIONS_RUNBOOK.md](docs/OPERATIONS_RUNBOOK.md) - 維運手冊
- [DOCKER_GUIDE.md](docs/DOCKER_GUIDE.md) - Docker 使用指南

### 專案狀態
- [STATUS.md](docs/STATUS.md) - 目前專案狀態與進度

## 系統架構摘要

- **16 個 Kafka Topics**: 10 channel + 6 business
- **26 個 Docker 容器**: 9 infra + 2 API/Gateway + 10 channel-job + 5 JOB
- **統一 Header/Body 結構**: 所有訊息使用相同格式
- **5 個電商平台**: Momo, Shopee, Yahoo, PChome, Cyberbiz

## 查看程式碼

完整程式碼請切換到 `main` 分支：

```bash
git checkout main
```

## 維護說明

本分支會定期從 main 分支同步文檔更新，但不包含程式碼變更。