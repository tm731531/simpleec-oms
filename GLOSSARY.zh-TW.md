# SimpleEC OMS - 術語表與詞彙表

本文檔提供整個 SimpleEC OMS 文檔中使用的術語、縮寫和關鍵概念的完整列表。

---

## 核心概念

### 商業術語

| 術語 | 定義 | 說明 |
|------|------|------|
| **OMS** | 訂單管理系統 (Order Management System) | 多通路訂單管理平台 |
| **通路** | 銷售平台或電商平台 | 蝦皮、媽媽購物、Yahoo、PChome、Cyberbiz、Shopline、Shopify |
| **商家** | 業務所有者/賣家 | 使用系統管理訂單和庫存 |
| **SKU** | 庫存單位 (Stock Keeping Unit) | 產品規格代碼 |
| **套包** | 在通路上銷售的商品套裝 | 通路特定的商品配置 |
| **訂單** | 顧客購買記錄 | 包含來自一個或多個套包的多個項目 |
| **項目** | 訂單中的單一行項目 | 參考特定的套包和數量 |
| **退貨** | 顧客退貨請求 | 啟動、批准和履行流程 |
| **出貨** | 訂單的履行 | 追蹤和交付狀態 |
| **SLA** | 服務水準協議 (Service Level Agreement) | 回應時間承諾 |
| **多通路** | 多個銷售渠道 | 跨平台整合管理 |
| **訂單履行** | 訂單處理和配送 | 端到端訂單交付 |
| **庫存管理** | 庫存管理 | 即時數量追蹤 |

### 財務術語

| 術語 | 定義 | 說明 |
|------|------|------|
| **ARR** | 年經常性收入 (Annual Recurring Revenue) | 逐年收入預測 |
| **MRR** | 月經常性收入 (Monthly Recurring Revenue) | 月度收入預測 |
| **CAC** | 客戶獲取成本 (Customer Acquisition Cost) | 平均獲取一個顧客的成本 |
| **LTV** | 客戶生命週期價值 (Customer Lifetime Value) | 一個顧客產生的總預期收入 |
| **流失率** | 客戶流失率 (Churn Rate) | 每個期間流失的顧客百分比 |
| **ARPU** | 每用戶平均收入 (Average Revenue Per User) | 收入除以用戶數 |
| **NPS** | 淨推薦值 (Net Promoter Score) | 顧客滿意度指標 (-100 到 100) |
| **毛利率** | 毛利潤率 (Gross Margin) | 收入減去商品成本 |
| **營運利潤率** | 營運利潤率 (Operating Margin) | 淨收入除以收入 |

---

## 技術架構

### 事件驅動架構

| 術語 | 定義 | 說明 |
|------|------|------|
| **事件** | 描述發生了什麼的不可改變事實 | 訂單建立、付款收到、出貨追蹤更新 |
| **事件流** | 有序的事件序列 | 僅附加日誌，不可改變的歷史 |
| **處理器** | 處理事件的元件 | 消費消息並更新系統狀態 |
| **任務類型** | 事件/任務的分類 | FETCH_ORDERS、PROCESS_ORDER、SHIP_ORDER 等 |
| **標頭** | 事件中繼資料用於路由 | taskType、merchantId、platformId、requestId、timestamp |
| **主體** | 事件業務資料 | 訂單詳情、項目信息、退貨詳情 |
| **isRollback** | 回補訂單標籤 | `true` = 歷史資料，`false` = 新訂單 |
| **真實來源** | 權威資料儲存 | `order.process` 和 `return.process` 主題 |

### Kafka 與消息隊列

| 術語 | 定義 | 說明 |
|------|------|------|
| **Kafka** | 分散式事件串流平台 | 核心消息代理 |
| **主題** | 消息的邏輯通道 | SimpleEC OMS 中有 16 個主題 |
| **分區** | 主題的有序子序列 | 並行處理能力 |
| **消費者組** | 相關消費者的集合 | 每個組保持獨立偏移量 |
| **消費者** | 讀取消息的流程 | 處理特定 TaskType 的任務 |
| **生產者** | 發送消息的流程 | 排程器、通路任務、後端任務 |
| **偏移量** | 分區中的位置 | 追蹤消費進度 |
| **DLT** | 死信主題 (Dead Letter Topic) | 處理無法處理的消息 |
| **保留期** | 訊息保留多長時間 | 可配置（主題預設 1 天，DLT 預設 30 天） |
| **KRaft** | Kafka Raft 協定 | 無 ZooKeeper 的模式（本項目使用） |

### 消息主題

| 主題 | 類型 | 用途 | 保留期 |
|------|------|------|--------|
| **{platform}.fast** | 通路 | 快速任務 (< 5 秒): SHIP_ORDER、UPDATE_PRICE、UPDATE_INVENTORY、APPROVE_RETURN | 1 天 |
| **{platform}.slow** | 通路 | 慢速任務 (< 5 分鐘): FETCH_ORDERS、FETCH_ORDER_DETAIL、FETCH_RETURNS、SYNC_PACK | 1 天 |
| **order.process** | 業務 | 核心訂單工作流 - 真實來源 | 1 天 |
| **return.process** | 業務 | 核心退貨工作流 - 真實來源 | 1 天 |
| **task.backend** | 系統 | 內部非同步任務 (SYNC_PRODUCT、套包→產品映射) | 1 天 |
| **task.frontend** | 系統 | 前端事件通知 | 1 天 |
| **scheduler** | 系統 | 排程任務觸發 | 1 天 |
| **task.failed** | 系統 | 臨時失敗（將重試） | 1 天 |
| **task.dlt** | 系統 | 永久失敗（毒丸） | 30 天 |

**注意**: 平台 = cyberbiz、momo、shopee、yahoo、pchome、easystore、shopline

### 資料庫與持久化

| 術語 | 定義 | 說明 |
|------|------|------|
| **PostgreSQL** | 關係型資料庫 | 主要資料儲存 |
| **Redis** | 記憶體內快取 | 去重、工作階段快取 |
| **綱要** | 資料庫結構定義 | SimpleEC OMS 中有 16 個表 |
| **實體** | ORM 表示法 | MyBatis-Plus 映射 |
| **JSONB** | JSON 二進制格式 | PostgreSQL 原生類型，用於靈活欄位 |
| **NanoID** | 唯一識別碼 | UUID 的輕量級替代品 |
| **PII** | 個人可識別信息 | 加密欄位：買家姓名、電話、電子郵件、地址 |
| **去重** | 防止重複處理 | 基於 Redis 的冪等性 |
| **AOF** | 僅附加文件 (Append-Only File) | Redis 持久化模式 |

---

## 平台集成

### 通路 API

| 平台 | 類型 | 主要功能 | 說明 |
|------|------|--------|------|
| **蝦皮** | 電商平台 | 游標分頁、狀態生命週期 (待支付→已確認→已出貨→已完成) | 無批量詳情 API |
| **媽媽購物** | 電商平台 | 項目級記錄、無狀態分類、需要聚合 | IP 白名單 |
| **Yahoo購物中心** | 電商平台 | 僅時間範圍查詢、通過資料評估訂單狀態 | 歷史記錄有限 |
| **PChome** | 電商平台 | 待實現 | 未來實現 |
| **Cyberbiz** | SaaS | 待實現 | 台灣 SaaS 平台 |
| **easystore** | SaaS | 一次取 50 筆完整訂單、嚴格速率限制 | 預設 7 天窗口 |
| **Shopify** | SaaS | 標準 Shopify API | OAuth 2.0 整合 |

### 平台特定術語

| 術語 | 定義 | 平台 |
|------|------|------|
| **create_time_from/to** | 訂單建立時間範圍過濾器 | 蝦皮 |
| **updated_after** | 自時間戳以來更新的訂單 | Yahoo |
| **from_date/to_date** | 日期範圍過濾器 | easystore |
| **游標分頁** | 使用游標令牌的分頁 | 蝦皮 |
| **偏移分頁** | 使用頁面偏移的分頁 | 媽媽購物 |
| **項目級** | 個別行項目記錄 | 媽媽購物 |
| **訂單聚合** | 按訂單號分組項目 | 媽媽購物需要手動聚合 |

---

## 系統元件

### 核心服務

| 服務 | 連接埠 | 用途 | 技術 |
|------|--------|------|------|
| **API** | 8083 | 商家 REST API | Spring Boot 3.5 |
| **網關** | 8081 | 通路 Webhook 端點 | Spring Boot |
| **通路任務** | 內部 | 通路資料同步 | 10 個並行任務 (5 個平台 × 2 個速度) |
| **訂單任務** | 內部 | 訂單處理工作流 | Kafka 消費者 |
| **後端任務** | 內部 | 內部非同步處理 | Kafka 消費者 |
| **排程任務** | 內部 | 排程觸發 | HeartbeatTimer |
| **Postgres** | 5433 | 主要資料庫 | PostgreSQL 16 |
| **Redis** | 6379 | 快取與去重 | Redis 7 (AOF) |
| **Kafka** | 9092 | 事件串流 | Kafka 3.7.1 (KRaft) |
| **Nginx** | 8089 | 反向代理 | 前端閘道 |
| **Grafana** | 3000 | 監控儀表板 | 可觀察性 |

### 前端服務

| 服務 | 連接埠 | 用途 | 技術 |
|------|--------|------|------|
| **使用者應用程式** | 5173 (開發)、8089 (代理) | 商家 UI | Vue 3 + Vite |
| **管理應用程式** | 8084 (開發)、8089 (代理) | 平台管理 UI | Vue 3 + Vite |

---

## 營運與部署

### Docker 與容器

| 術語 | 定義 | 說明 |
|------|------|------|
| **Docker** | 容器執行時 | 容器化部署 |
| **Docker Compose** | 容器編排 | 多容器設定 |
| **容器** | 隔離的應用程式環境 | 單一服務實例 |
| **映像** | 容器的藍圖 | 由 Dockerfile 建立 |
| **網路** | Docker 內部網路 | 服務間通訊 |
| **卷** | 持久化存儲 | 跨重啟資料持久化 |
| **健康檢查** | 服務就緒探測 | 確認服務可用性 |

### 監控與日誌

| 術語 | 定義 | 說明 |
|------|------|------|
| **Grafana** | 視覺化儀表板 | 即時監控 |
| **Prometheus** | 指標蒐集 | 時間序列資料庫 |
| **Loki** | 日誌聚合 | 來自服務的 JSON 日誌 |
| **Tempo** | 分散式追蹤 | 追蹤視覺化 |
| **OpenTelemetry** | 可觀察性標準 | 統一工具化 |
| **MDC** | 對應診斷內容 | 上下文日誌 (traceId、spanId、merchantId) |
| **JSON 日誌** | 結構化日誌格式 | Logstash 相容日誌 |

### 部署與營運

| 術語 | 定義 | 說明 |
|------|------|------|
| **分支** | Git 分支變體 | main、docs-only、功能分支 |
| **提交** | Git 版本快照 | 不可改變的代碼歷史 |
| **CI/CD** | 持續整合/部署 | 自動化測試和部署 |
| **熱修復** | 緊急修復 | 立即應用於生產環境 |
| **發布** | 版本化軟體發布 | 帶版本的標籤提交 |
| **部署** | 漸進式部署 | 分階段部署以防止中斷 |
| **回滾** | 還原到先前版本 | 撤銷最近的更改 |
| **手冊** | 營運程序 | 逐步故障排除指南 |

---

## 資料處理

### 任務類型

| 任務類型 | 類別 | 用途 | 消費者 | 回應時間 |
|---------|------|------|--------|---------|
| **FETCH_ORDERS** | 通路 | 從通路檢索訂單 | 通路任務 | < 5 分鐘 |
| **FETCH_ORDER_DETAIL** | 通路 | 取得詳細訂單信息 | 通路任務 | < 5 分鐘 |
| **FETCH_RETURNS** | 通路 | 檢索退貨請求 | 通路任務 | < 5 分鐘 |
| **FETCH_RETURN_DETAIL** | 通路 | 取得退貨詳情 | 通路任務 | < 5 分鐘 |
| **SYNC_PACK** | 通路 | 同步產品套包 | 通路任務 | < 5 分鐘 |
| **SYNC_PRODUCT** | 後端 | 從套包建立產品 | 後端任務 | < 5 秒 |
| **PROCESS_ORDER** | 工作流 | 訂單業務邏輯 | 訂單任務 | < 5 秒 |
| **SHIP_ORDER** | 通路 | 通知通路出貨 | 通路任務 | < 5 秒 |
| **APPROVE_RETURN** | 通路 | 批准退貨請求 | 通路任務 | < 5 秒 |
| **UPDATE_INVENTORY** | 通路 | 推送庫存更新 | 通路任務 | < 5 秒 |
| **UPDATE_PRICE** | 通路 | 推送價格更新 | 通路任務 | < 5 秒 |

---

## 團隊與協作

| 術語 | 定義 | 說明 |
|------|------|------|
| **利益相關者** | 對項目有利益的人 | 投資者、使用者、團隊成員 |
| **拉取請求** | 代碼審查機制 | PR (Pull Request) |
| **代碼審查** | 變更的同行驗證 | 合並前的品質閘道 |
| **合併** | 合併分支 | 整合變更到 main |
| **議題** | 問題或功能請求 | 在 GitHub Issues 中追蹤 |
| **文檔** | 技術指南和參考 | docs/ 中的 Markdown 文件 |
| **手冊** | 逐步程序 | 營運文檔 |
| **RACI** | 責任矩陣 | 決策的明確所有權 |

---

## 常見縮寫

| 縮寫 | 全名 | 類別 |
|------|------|------|
| **OMS** | 訂單管理系統 (Order Management System) | 核心 |
| **API** | 應用程式編程介面 (Application Programming Interface) | 技術 |
| **REST** | 表現層狀態轉移 (Representational State Transfer) | 技術 |
| **JWT** | JSON 網頁令牌 (JSON Web Token) | 安全 |
| **HTTP/HTTPS** | 超文本傳輸協定 (HyperText Transfer Protocol) | 網路 |
| **TCP/IP** | 傳輸控制協定 (Transmission Control Protocol) | 網路 |
| **SSL/TLS** | 安全套接層/傳輸層安全 (Secure Socket Layer / Transport Layer Security) | 安全 |
| **JSON** | JavaScript 物件標記法 (JavaScript Object Notation) | 資料格式 |
| **XML** | 可擴展標記語言 (Extensible Markup Language) | 資料格式 |
| **CRUD** | 建立、讀取、更新、刪除 (Create, Read, Update, Delete) | 資料庫 |
| **SQL** | 結構化查詢語言 (Structured Query Language) | 資料庫 |
| **NoSQL** | 非關聯型資料庫 (Non-relational databases) | 資料庫 |
| **ORM** | 物件關聯映射 (Object-Relational Mapping) | 資料庫 |
| **PII** | 個人可識別信息 (Personally Identifiable Information) | 安全 |
| **AES** | 進階加密標準 (Advanced Encryption Standard) | 密碼學 |
| **GCM** | Galois/Counter 模式 (Galois/Counter Mode) | 密碼學 |
| **SLA** | 服務水準協議 (Service Level Agreement) | 商業 |
| **RTO** | 恢復時間目標 (Recovery Time Objective) | 災難恢復 |
| **RPO** | 恢復點目標 (Recovery Point Objective) | 災難恢復 |
| **CI/CD** | 持續整合/部署 (Continuous Integration/Deployment) | DevOps |
| **VCS** | 版本控制系統 (Version Control System) | DevOps |
| **UI** | 使用者介面 (User Interface) | 前端 |
| **UX** | 使用者體驗 (User Experience) | 前端 |
| **URL** | 統一資源定位符 (Uniform Resource Locator) | 網路 |
| **ENV** | 環境 (Environment) (變數) | 配置 |
| **SSH** | 安全殼層 (Secure Shell) | 網路 |
| **SSH 鑰匙** | 公私鑰對 (Public-private key pair) | 安全 |
| **YAML** | YAML 不是標記語言 (YAML Ain't Markup Language) | 配置 |
| **KRaft** | Kafka Raft 協定 (Kafka Raft Protocol) | Kafka |
| **AOF** | 僅附加文件 (Append-Only File) | Redis |
| **CAP** | 一致性、可用性、分區容錯性 (Consistency, Availability, Partition tolerance) | 分散式系統 |
| **ACID** | 原子性、一致性、隔離性、持久性 (Atomicity, Consistency, Isolation, Durability) | 資料庫 |
| **BASE** | 基本可用、軟狀態、最終一致性 (Basically Available, Soft state, Eventually consistent) | 分散式系統 |
| **MVP** | 最小可行產品 (Minimum Viable Product) | 產品開發 |
| **POC** | 概念驗證 (Proof of Concept) | 產品開發 |
| **UAT** | 使用者驗收測試 (User Acceptance Testing) | 品質保證 |
| **E2E** | 端到端 (End-to-End) | 測試 |
| **QA** | 品質保證 (Quality Assurance) | 測試 |

---

## 版本與狀態代碼

### 系統版本

| 元件 | 目前版本 | 說明 |
|------|---------|------|
| **Java** | 17 | LTS 版本 |
| **Spring Boot** | 3.5.0 | 最新穩定版 |
| **Gradle** | 8.14.4 | 建立工具 |
| **PostgreSQL** | 16 | 最新主要版本 |
| **Redis** | 7 | 最新主要版本 |
| **Kafka** | 3.7.1 | 最新穩定版 |
| **Docker** | 20.10+ | 最低支援版本 |
| **Docker Compose** | 2.0+ | 最低支援版本 |
| **Node.js** | 18+ | 前端建立 |
| **Vue** | 3 | 前端框架 |
| **Vite** | 最新 | 前端建立工具 |

### HTTP 狀態代碼

| 代碼 | 含義 | 使用 |
|------|------|------|
| **200** | 確定 (OK) | 成功請求 |
| **201** | 已建立 (Created) | 資源成功建立 |
| **202** | 已接受 (Accepted) | 接受處理請求 |
| **204** | 無內容 (No Content) | 成功，無回應主體 |
| **400** | 錯誤的請求 (Bad Request) | 無效的請求格式 |
| **401** | 未授權 (Unauthorized) | 需要驗證 |
| **403** | 禁止 (Forbidden) | 權限不足 |
| **404** | 未找到 (Not Found) | 資源不存在 |
| **500** | 伺服器內部錯誤 (Internal Server Error) | 伺服器錯誤 |
| **503** | 服務無法使用 (Service Unavailable) | 服務暫時關閉 |

---

## 相關文檔

欲瞭解更多這些術語的內容，請參閱：
- [CORE_CONTRACTS.md](docs/3-EVENT-FLOW/CORE_CONTRACTS.md) - 消息結構定義
- [DATA_FLOW_MAPPING.md](docs/3-EVENT-FLOW/DATA_FLOW_MAPPING.md) - 消息如何流經系統
- [PLATFORM_MAPPING.md](docs/4-SCHEMA/PLATFORM_MAPPING.md) - 平台特定詳情
- [ARCHITECTURE_OVERVIEW.md](docs/1-ARCHITECTURE/ARCHITECTURE_OVERVIEW.md) - 系統概覽
- [CHANNEL_IMPLEMENTATION_GUIDE.md](docs/7-IMPLEMENTATION/CHANNEL_IMPLEMENTATION_GUIDE.md) - 如何添加新平台

---

**最後更新**: 2026 年 2 月
**維護者**: SimpleEC 團隊
