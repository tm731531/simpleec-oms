# SimpleEC OMS - 簡易電商訂單管理系統

[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.7-black?logo=apachekafka)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red?logo=redis)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue?logo=docker)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/License-Private-lightgrey)]()

**選擇語言:** [English](README.md) | [繁體中文](README.zh-TW.md)

> 專為台灣電商市場打造的多通路訂單管理系統 (OMS)，以事件驅動架構統一管理訂單、庫存、出貨與退貨。

---

## 什麼是 SimpleEC OMS？

SimpleEC OMS 是一套事件驅動的訂單管理系統，將來自多個電商平台的訂單、庫存、出貨和退貨統一到單一操作介面。專為同時經營 Cyberbiz、蝦皮、MOMO、PChome、Yahoo、Shopline、Shopify 的台灣賣家設計。

不再需要登入 7 個不同的賣家後台 — SimpleEC OMS 透過 Kafka 事件串流自動同步所有訂單資料，提供集中化的管理介面。

## 核心功能

| 功能 | 說明 |
|------|------|
| **多通路同步** | 自動從 7 個平台（Cyberbiz、蝦皮、MOMO、PChome、Yahoo、Shopline、Shopify）抓取訂單 |
| **統一訂單管理** | 在同一介面查看、篩選、管理所有來源平台的訂單 |
| **出貨工作流** | 完整倉庫流程：揀貨、包裝、貼標、批次出貨管理 |
| **庫存同步** | 將庫存更新推送回各個通路 |
| **退貨處理** | 跨平台集中式退貨/退款處理 |
| **銷售報表** | 依通路彙總的每日統計與歷史趨勢追蹤 |
| **事件驅動架構** | 基於 Kafka 的非同步處理，內建自動重試與死信佇列 |
| **可觀測性** | 內建 Grafana + Prometheus + Loki + Tempo，涵蓋指標、日誌與追蹤 |

## 系統架構

```
                        ┌─────────────────────────┐
                        │     Nginx (8089)         │
                        │  / → 商家前台 (Vue 3)    │
                        │  /admin → 管理後台       │
                        │  /api → Spring Boot API  │
                        └────────────┬────────────┘
                                     │
┌────────────────────────────────────┼────────────────────────────────────┐
│                           Spring Boot API (8083)                        │
│  JWT 認證 · REST 控制器 · 出貨服務 · 統計服務                           │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                      ▼
    ┌─────────────────┐   ┌──────────────────┐   ┌─────────────────┐
    │  Apache Kafka    │   │   PostgreSQL 16   │   │     Redis 7     │
    │  16 個主題       │   │   19+ 張資料表    │   │  快取 + 去重    │
    │  KRaft 模式      │   │   AES-256 加密    │   │  分散式鎖       │
    └────────┬────────┘   └──────────────────┘   └─────────────────┘
             │
    ┌────────┴────────────────────────────────┐
    │         Kafka 消費者任務                  │
    │  通路任務 · 訂單任務 · 重試任務           │
    │  後端任務 · 排程任務 · 前端任務           │
    └─────────────────────────────────────────┘
```

### 事件流如何運作？

1. **排程器**每 5 分鐘發送心跳時間戳到 Kafka
2. **通路任務**收到時間戳後，根據各平台特性自主決定時間窗口和分頁策略，呼叫平台 API
3. 通路任務將各平台特有格式轉換為統一 OMS 結構，發布到 `order.process` 主題
4. **訂單任務**將訂單持久化到 PostgreSQL，自動去重
5. 出貨時，**出貨服務**發布 `SHIP_ORDER` 事件回平台的 Kafka 主題
6. **通路任務**呼叫平台的出貨確認 API

## 支援平台

| 平台 | 訂單同步 | 出貨 | 退貨 | 庫存 |
|------|:--------:|:----:|:----:|:----:|
| Cyberbiz | ✅ | ✅ | ✅ | ✅ |
| 蝦皮 (Shopee) | ✅ | ✅ | ✅ | ✅ |
| MOMO | ✅ | ✅ | ✅ | - |
| PChome | ✅ | ✅ | - | - |
| Yahoo | ✅ | ✅ | - | - |
| Shopline | ✅ | - | - | - |
| Shopify | ✅ | - | - | - |

## 技術棧

| 層級 | 技術 |
|------|------|
| 程式語言 | Java 17 |
| 框架 | Spring Boot 3.5、MyBatis-Plus |
| 訊息佇列 | Apache Kafka 3.7（KRaft 模式，無 ZooKeeper） |
| 資料庫 | PostgreSQL 16 |
| 快取 | Redis 7（AOF 持久化） |
| 前端 | Vue 3 + Vite |
| 認證 | JWT + AES-256-GCM（PII 加密） |
| 可觀測性 | OpenTelemetry + Grafana + Prometheus + Loki + Tempo |
| 容器化 | Docker Compose（26 個容器） |
| 建置 | Gradle 8.14，11 個模組 |

## 快速開始

### 環境需求

- Docker 20.10+ 和 Docker Compose 2.0+
- JDK 17+（推薦使用 [sdkman](https://sdkman.io/)）

### 安裝

```bash
git clone https://github.com/tm731531/simpleec-oms.git
cd simpleec-oms

# 編譯
./gradlew clean build -x test

# 啟動全部 26 個容器
docker compose up -d

# 驗證
curl http://localhost:8083/api/health
```

### 服務入口

| 服務 | URL | 說明 |
|------|-----|------|
| 商家前台 | http://localhost:8089 | 訂單與通路管理 |
| 管理後台 | http://localhost:8089/admin/ | 平台管理 |
| API | http://localhost:8083/api | REST API |
| Kafka UI | http://localhost:8088 | 主題與消費者監控 |
| Grafana | http://localhost:3000 | 指標與日誌儀表板 |

**測試帳號：** `admin@a00000.com` / `pass123456`

## 模組結構

```
simpleec-oms/
├── simpleec-common        # 共用：列舉、模型、工具類
├── simpleec-core          # 核心：實體、倉儲、服務
├── simpleec-channel       # 通路適配器（各平台 API 客戶端）
├── simpleec-api           # REST API（Spring Boot :8083）
├── simpleec-gateway       # 對外閘道：Webhook、ERP
├── simpleec-channel-job   # 通路同步消費者（10 個實例）
├── simpleec-order-job     # 訂單處理消費者
├── simpleec-scheduler-job # 心跳排程器
├── simpleec-backend-job   # 非同步後端任務
├── simpleec-frontend-job  # 前端事件消費者
└── simpleec-retry-job     # 重試 + 死信佇列路由
```

## Kafka 主題

SimpleEC 使用 16 個 Kafka 主題，依功能分類：

| 類別 | 主題 | 用途 |
|------|------|------|
| 通路（快速） | `{platform}.fast` x6 | 快速操作：出貨、改價、庫存同步（<5s） |
| 通路（慢速） | `{platform}.slow` x6 | 資料同步：抓取訂單、退貨、商品（<5min） |
| 業務 | `order.process`、`return.process` | 訂單/退貨事件的唯一事實來源 |
| 系統 | `scheduler`、`task.backend`、`task.frontend` | 內部協調 |
| 錯誤 | `task.failed`、`task.dlt` | 重試佇列（保留 1 天）與死信（保留 30 天） |

## 文件

| 文件 | 說明 |
|------|------|
| [架構總覽](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md) | 系統設計與元件圖 |
| [核心契約](docs/3-EVENT-FLOW/CORE_CONTRACTS.md) | Kafka 訊息格式與主題定義 |
| [資料流映射](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md) | API 到 Kafka 到資料庫的映射 |
| [資料庫 Schema](docs/4-SCHEMA/SCHEMA.md) | 全部資料表 DDL（19+ 張表） |
| [通路實作指南](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md) | 如何新增平台 |
| [營運手冊](docs/6-OPERATIONS/OPERATIONS_RUNBOOK.md) | 部署與故障排除 |

## 常見問題

### 如何新增電商平台？

實作平台的 `ChannelAdapter`，建立帶有 `@ChannelHandler(platform = "xxx")` 註解的處理器，並新增平台的 `.fast` 和 `.slow` Kafka 主題。詳見[通路實作指南](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md)。

### 訂單去重如何運作？

每張訂單以 `channelId + channelOrderId` 唯一識別。透過 Redis 去重防止重複處理。`OrderUpsertConsumer` 在新增/更新前會檢查是否已存在。

### 為什麼用 Kafka 而不是 REST 做服務間通訊？

Kafka 提供可靠的非同步處理，內建自動重試、死信佇列和背壓處理。各平台 API 回應時間差異很大（1 秒到 30 秒），Kafka 將同步速度與處理速度解耦。

### 敏感資料如何保護？

買家 PII（姓名、電話、Email、地址）使用 AES-256-GCM 加密儲存，透過 MyBatis 透明型別處理器實現。每個商家有獨立的加密金鑰。

### 可以不用 Docker 執行嗎？

可以。每個模組都是標準的 Spring Boot 應用程式。需要另外啟動 PostgreSQL 16、Redis 7 和 Kafka 3.7，然後為每個模組配置 `application.yml`。

---

**版本**: v0.1-MVP | **狀態**: 完全運作中 | **最後更新**: 2026-03

## 授權

私有 — 保留所有權利。
