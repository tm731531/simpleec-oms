# DESIGN.md 重寫計畫

## 第一輪修正（13 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 1 | 重新整理文件結構 | 全文 |
| 2 | 庫存同步不是10分鐘，一天兩次就夠 | §5 seed data, §3 |
| 3 | 拉單24小時，無時段限制 | §5 task_schedule (移除 active_hours), Dispatcher |
| 4 | 收訂單 vs 整理訂單 = 兩支獨立 JOB | §5 核心設計, §3 新增 order.process topic |
| 5 | 報表/彙整是 Backend JOB 做的，事件只告訴時間 | §5 BackendJob 設計 |
| 6 | 心跳頻率可設定，不是硬編 1 秒 | §5 Timer 設計 |
| 7 | 參考 call-to-backend-job / recover-data-job 模式 | §5 Job 架構核心 |
| 8 | 不是每支 JOB 都聽快慢，JOB 本身就是為快或慢而存在 | §3, §5 Job 分類 |
| 9 | Partition key 依場景不同 | §3 partition key 策略表 |
| 10 | 去重：通路JOB→訂單整理JOB 之間有 Hash 快取 | §4 新增 Order Hash Dedup |
| 11 | 移除 PipelineExecutor，每支 JOB 自治路由 | 刪除 §5-A, 改寫 §5 |
| 12 | 通路任務有大有小，訂單抓取不能再拆 | §7 通路任務粒度說明 |
| 13 | 訂單與整理分開+hash快取原因：雙十一突波 | §4, §5 |

## 第二輪修正（5 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 14 | **JOB 粒度收斂**：7支細JOB→收斂為大粒度（平台JOB、訂單整理JOB、時間管理排程JOB、時間轉換工作JOB、後台工作JOB、前台工作JOB、Web API）。同一支JOB同樣CODE，透過Helm參數吃不同TOPIC | §5 全面改寫 JOB 架構 |
| 15 | **失敗不retry**：做了就做了，queue一定消化。成功/失敗寫LOG。另一支JOB專門把失敗LOG轉成要打回去的TOPIC（有些不能打回去），所有開關/時間控制/delay都在那支寫 | §3.9 失敗處理全面改寫 |
| 16 | **Topic per-channel**：不是統一channel.fast/slow，而是shopee.slow、shopee.fast、momo.slow、momo.fast。每個平台限速/規則不同，不能用同topic不同group | §3.3 Topic設計全面改寫, §3.4 Partition Key |
| 17 | **排程不需要資料表**：心跳純發送到scheduler topic，consumer決定時間該做什麼。不需要task_schedule表。工作內容和流程依賴在TOPIC上的事件和邏輯，不是SQL | §6 排程設計全面改寫, §1 移除task_schedule |
| 18 | **Helm管並行**：同一支JOB同樣CODE，Helm設定要讀的TOPIC。不是@KafkaListener硬編topics，而是參數化 | §5.6 並行度管理改寫 |

## 新文件結構（第二輪修正後）

```
§0. 現狀盤點 — 保留
§1. 資料庫設計 — 移除 task_schedule 表
§2. 後端架構 — 保留
§3. MQ 設計（Kafka）— 大幅改寫
    §3.1 為什麼 Kafka（保留）
    §3.2 快慢分離（改寫：同一支JOB透過Helm吃不同TOPIC，event本身區分工作量）
    §3.3 Topic 設計（改寫：per-channel → shopee.slow/fast, momo.slow/fast...）
    §3.4 Partition Key 策略（保留核心概念，更新 topic 名稱）
    §3.5 KafkaConfig（更新 topic 列表 → 動態建立 per-channel topics）
    §3.6 Producer（保留）
    §3.7 失敗處理（全面改寫：不retry，寫LOG，另一支JOB轉失敗LOG為重打TOPIC）
    §3.8 application.yml（保留）
§4. Redis 快取設計 — 保留（Hash Dedup保留）
§5. JOB 架構（第二輪核心改寫）
    §5.1 JOB = 自治單元（保留核心概念）
    §5.2 JOB 類型總覽（收斂為大粒度）
        - 平台 JOB（ChannelJob）— 所有跟平台溝通的收斂成一支
        - 訂單整理 JOB（OrderProcessJob）
        - 時間管理排程 JOB（SchedulerJob）— 心跳→scheduler topic→決定時間該做什麼
        - 時間轉換工作 JOB（TaskDispatchJob）— 把時間事件轉成各TOPIC
        - 後台工作 JOB（BackendJob）
        - 前台工作 JOB（FrontendJob）
        - 失敗重打 JOB（RetryDispatchJob）— 專門把失敗LOG轉成重打TOPIC
        - Web API — 各自一個
    §5.3 各 JOB 詳細設計
    §5.4 JOB 間自治路由（保留核心）
    §5.5 Helm 參數化管理（同一支CODE透過Helm吃不同TOPIC/concurrency）
§6. 排程設計（全面改寫）
    §6.1 心跳 = 純發送者（送到 scheduler topic）
    §6.2 Scheduler Consumer — 收到心跳後決定時間該做什麼
    §6.3 不需要 task_schedule 資料表（邏輯在程式裡）
    §6.4 為什麼不用 Quartz（保留）
§7. 通路串接設計 — 保留
§8. 前端設計 — 保留
§9. 部署架構 — 更新 JOB 方塊
§10. 開發順序 — 改寫
§11. 技術決策 — 改寫
```

## 第二輪刪除的內容
- task_schedule 表 DDL + seed data（§1, §6.2, §6.3）
- 7支細粒度JOB設計（被大粒度JOB取代）
- 失敗 retry 機制（ack + re-produce + retryCount + DLQ）
- 統一 channel.fast / channel.slow topic（改為 per-channel）
- @KafkaListener 硬編 topics 的寫法（改為 Helm 參數化）
- ScheduleDispatcher 的 task_schedule 查詢邏輯（改為純事件驅動）
- Memory-first ConcurrentHashMap 排程快取（不再需要）

## 第二輪新增的核心內容
- 失敗LOG + RetryDispatchJob（專門把失敗LOG轉成重打TOPIC，含開關/時間控制/delay）
- per-channel topic 設計（shopee.slow/fast, momo.slow/fast...）
- Helm 參數化 JOB 設計（同CODE不同TOPIC）
- Scheduler Topic + Consumer 純事件驅動排程
- 大粒度 JOB 收斂（6+1 支 JOB + Web API）

## 第三輪修正（6 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 19 | **RetryDispatchJob 失敗次數追蹤**：每次重打在 QUEUE 訊息上標註 retryCount，超過 maxRetry 直接丟掉（參考舊系統 flow-retry-times <= 3） | §3.9, §5.3.6 RetryDispatchJob |
| 20 | **移除 K8s/Helm 部署假設**：不一定用 pod，部署方式改為通用參數化（環境變數 / 設定檔），支持 Docker Compose / K8s / 裸機 | §3.2, §5.1, §5.2, §5.6, §9.2, §10, §11 全文 |
| 21 | **訂單快取 key 結構修正**：`merchantId:channelId:orderId` : hash(order)，ChannelJob + OrderProcessJob 兩邊 SYNC 同一個 key 結構 | §4.1, §4.6, §5.3.1, §5.3.2, §5.8 |
| 22 | **Redis 鎖幾乎不需要**：Kafka partition key 天然保證同 key 有序，分散式鎖設計好就不需要。移除 DistributedLock 實作，標記為「幾乎不需要」 | §4.5 |
| 23 | **ChannelJob 參考 action-to-platform 模式**：ActionFactory + ActionService 4 步生命週期（setting → getPlatformTokens → verifyNeedData → doAction），對齊舊系統 40+ ActionService 實作 | §5.1, §5.3.1, §7.5 |
| 24 | **全文重新整理**：統一命名（Worker → ActionService）、移除衝突、確保 §3-§11 一致性 | 全文 |

## 第三輪修改的內容
- RetryDispatchJob: retryCount >= maxRetry → 丟掉（§3.9, §5.3.6, Java code）
- 所有 Helm / pod 字眼改為通用 instance / 環境變數 / 部署設定
- order:hash key 改為 `{merchantId}:{channelId}:{orderId}`，兩邊 SYNC 說明
- §4.5 DistributedLock 改為「幾乎不需要」說明
- §5.1 新增舊系統 action-to-platform 完整架構對照
- §5.3.1 ChannelJob 改為 ActionFactory + ActionService 模式
- §7.5 Consumer 調度改為 FetchOrdersActionService 範例
- §11 新增 4 個決策：ChannelJob 模式、訂單去重 key、Redis 鎖、失敗次數追蹤
- 全文命名統一：ChannelWorker → ActionService, FetchOrderWorker → FetchOrdersActionService

## 第四輪修正（3 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 25 | **各 JOB 獨立 Spring Boot**：不是所有 JOB 在同一個 process。ChannelJob 是唯一「同 CODE 不同設定」的（參考舊系統），其他 JOB 各自獨立 Spring Boot application。API 和 JOB 之間唯一耦合是 Kafka topic | §2, §5, §9, §10, §11 全文 |
| 26 | **API 角色明確**：simpleec-api 只做「看平台、塞設定、看資料、丟事件」，不消費 Kafka，不直接呼叫 JOB。後端 JOB 不是 API 的後流，中間都透過 Kafka topic 介接 | §2.1 新增, §5.7 改寫, §9 改寫 |
| 27 | **Gradle 模組結構重建**：共用 3 module (common/core/channel) + 8 個 Boot module (api + 7 JOB) + 前端。docker-compose 16 個 container | §2.2 改寫, §9.1 全面改寫, §9.3 新增, §10 改寫 |

## 第四輪修改的內容
- §2.1 新增「系統角色分離」— API 角色 vs JOB 角色 vs ChannelJob 唯一特例
- §2.2 新增「Gradle 模組總覽」— 共用 module + Boot module + 前端
- §2 子章節重新編號（2.2→2.3, 2.3→2.4）
- §5 開頭改寫：每支 JOB 獨立 Spring Boot，ChannelJob 是唯一特例
- §5.2 改寫：JOB 表加 Gradle Module 欄位，說明為什麼各自獨立
- §5.6 改寫：部署管理反映各自獨立 + ChannelJob 參數化
- §5.7 改寫：完整流程圖反映獨立 container + Kafka 介接
- §9.1 全面改寫：docker-compose.yml 16 個 service（含 x-common-env 共用設定）
- §9.2 改寫：架構圖反映各自獨立 container
- §9.3 新增：docker compose up 服務清單（16 個 container）
- §10 全面改寫：Phase 0 包含 Gradle 重構，各 Phase 反映逐步加入獨立 JOB container
- §11 改寫：架構決策改為「各 JOB 獨立 Spring Boot」，新增 ChannelJob/其他JOB/API角色 3 個決策

## 第四輪追加修正（1 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 28 | **沒有 channel-fast，一定是 per-channel per-speed**：每個 ChannelJob instance = 一個平台 × 一個速度（momo-fast, momo-slow, shopee-fast, shopee-slow...）。不存在「吃所有平台 fast」的 instance。8 個 ChannelJob instance（4 平台 × 2 速度），docker-compose 總計 19 container | §3.2, §3.5, §5.1, §5.3.1, §5.6, §9.1, §9.3, §10 全文 |

## 第四輪追加修改的內容
- 消除所有 `simpleec-channel-fast` / `channel-job-fast` / `momo.fast,shopee.fast` 混合寫法
- §3.2 快慢分離範例改為 8 個 instance（per-channel × per-speed）
- §3.5 並行度管理改為單平台 topic 範例
- §5.1 ChannelJob 說明改為「每個 instance = 一個平台 × 一個速度」
- §5.3.1 Topics/Group 改為 per-channel 範例
- §5.3.1 yaml 範例改為 momo-fast / momo-slow / shopee-fast / shopee-slow 各自獨立
- §5.6 部署範例改為 per-channel service
- §9.1 docker-compose 改為 8 個 ChannelJob service（4 fast + 4 slow）
- §9.3 服務清單改為 19 個 container
- §10 MVP 定義 / Phase 2 / Phase 3 / 路線圖 / 時間估算全部改為 19 container

## 第五輪修正（5 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 29 | **專案改名 SimpleEC**：simpleec → simpleec，目標是讓 EC 簡化。SimpleEC 有商標問題 | 全文 177 處 |
| 30 | **SchedulerJob + TaskDispatchJob 二合一**：SchedulerJob 收到心跳後直接查 DB 已啟用通路→發到各目標 topic。不需要 task.dispatch topic 和 TaskDispatchJob。少一支 JOB、少一個 topic | §2.2, §3.3, §3.5, §5.2, §5.3.3-5.3.5, §5.6, §5.7, §6.2, §6.4, §6.5, §9.1, §9.2, §9.3, §10, §11 |
| 31 | **失敗超過 maxRetry → 寫 DB LOG 表**：不是直接丟掉，而是寫入 DB 的 failed_task_logs 表，供 RD 定時查看、定時清理 | §5.3.5 RetryDispatchJob, §11 |
| 32 | **ChannelJob DB 讀寫範圍擴大**：不只讀 channels 表，依 action 不同可能讀 products、product_spec、sell_pack、orders 等 | §5.3.1 |
| 33 | **orders + order_items 合併**：不再拆 order_items。訂單就是訂單，含商品明細 JSONB。項次拆分是出貨階段的事 | §1.1, §1.2, §5.3.2, Entity |

## 第五輪修改的內容
- 全文 `simpleec` → `simpleec`（177 處），含 module 名、package 名、service 名、DB 名
- 刪除 simpleec-dispatch-job module（§2.2 Gradle 結構）
- 刪除 task.dispatch topic（§3.3 Topic 清單、§3.5 KafkaConfig）
- 刪除 TaskDispatchJob 整段程式碼和說明（§5.3.4 原本是 TaskDispatchJob → 改為 BackendJob）
- §5.3 子章節重新編號（5.3.5→5.3.4 BackendJob, 5.3.6→5.3.5 RetryDispatchJob）
- §5.2 JOB 類型總覽表：移除 TaskDispatchJob，SchedulerJob 改為「含分發邏輯」，JOB 從 7→6 支
- §6.2 SchedulerJob 程式碼完全重寫：加入 ChannelService + dispatch() 方法，直接發到各目標 topic
- §6.4 時區範例：移除 TaskDispatchJob 引用
- §5.3.5 RetryDispatchJob：超過 maxRetry 改為 `failedTaskLogService.save()` 寫 DB
- §5.3.1 ChannelJob：新增 DB 讀取說明（channels + products + product_spec + sell_pack + orders）
- §1.1 表關係：移除 order_items，加註「訂單不再拆項次」
- §1.2 核心表：orders 改為「含商品明細 JSONB，不再拆 order_items」
- §0 Entity：移除 OrderItem
- docker-compose: 移除 simpleec-dispatch-job service（§9.1）
- §9.2 架構圖：移除 Dispatch 方塊和 task.dispatch topic
- §9.3 服務清單：獨立 JOB 從 6→5，container 從 19→18
- §10 所有 Phase 的 container 數量 19→18
- §11 技術決策：JOB 粒度 7→6，調度引擎改為「判斷+分發一體」，失敗處理改為「寫 DB LOG」

## 第五輪刪除的內容
- TaskDispatchJob 整支 JOB（程式碼 + 說明 + docker service + Gradle module）
- task.dispatch topic
- order_items 表（合併入 orders）
- OrderItem entity
- simpleec 名稱（改為 simpleec）

## 第五輪追加修正（1 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 34 | **API 增加 Webhook 接收 + ERP 串接**：API 不只給前端看資料，還要接收通路 Webhook（驗簽→丟 Kafka）和提供 ERP 串接 REST API（JWT 認證） | §2.1, §2.3.4, §2.3.5, §2.4, §0 缺口表 |

## 第五輪追加修改的內容
- §2.1 API 角色新增 Webhook 接收 + ERP 串接
- §2.3.4 新增 Webhook 設計（WebhookHandler 介面 + WebhookEvent 統一結構）
  - **URL 路徑不硬編** — 各平台指定 webhook URL 格式，Nginx 配合路由
  - 各平台各自實作 WebhookHandler（驗簽 + 解析 + 回應格式各不同）
- §2.3.5 新增 ErpController（查詢 + 同步 API，JWT 認證，共用 Service 層）
- §2.4 API 端點清單新增 Webhook（路徑彈性）+ ERP 6 個 endpoint
- §5.2 Web API 職責新增「Webhook 接收、ERP 串接」
- §0 缺口表 Webhook 從 P2→P1，新增 ERP 串接 P1

## 第六輪修正（1 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 35 | **對外 API 跟前端 API 分開**：simpleec-api 只給前端用（看平台/塞設定/看資料/丟事件），新增 simpleec-gateway 獨立部署對外 API（Webhook + ERP）。前台時常客變、流量混雜，不應影響對外 API 穩定性 | §2.1, §2.2, §2.3.1, §2.3.4, §2.3.5, §2.4, §5.2, §5.6, §5.7, §9.1, §9.2, §9.3, §10, §11, §0 |

## 第六輪修改的內容
- §2.1 系統角色：simpleec-api（前端 API）+ simpleec-gateway（對外 API）分開描述，新增「為什麼分開」5 點理由
- §2.2 Gradle 模組：新增 simpleec-gateway Boot Module
- §2.3.1 JWT 認證：改為 simpleec-api + simpleec-gateway 共用
- §2.3.4 Webhook：從 simpleec-api → simpleec-gateway
- §2.3.5 ERP：從 simpleec-api → simpleec-gateway
- §2.3.5 下方備註：改為 simpleec-gateway 獨立部署
- §2.4 API 端點清單：分兩大區塊（simpleec-api :8080 + simpleec-gateway :8081），新增 Nginx 路由規則說明
- §5.2 JOB 總覽表：Web API 拆成「前端 API」+「對外 API」兩行
- §5.6 部署管理：新增 simpleec-gateway SERVER_PORT=8081
- §5.7 完整流程圖：頂部新增 simpleec-gateway 方塊，兩個 API 各自 TaskProducer → Kafka
- §9.1 docker-compose：新增 simpleec-gateway service（:8081）
- §9.2 架構圖：新增 simpleec-gateway 方塊，外部流量入口獨立
- §9.3 服務清單：Web API 拆成前端 API（1）+ 對外 API（1），total 18→19 container
- §10 所有 Phase 的 container 數量 18→19：
  - Phase 0 驗收加入 simpleec-gateway health check
  - Phase 4 改為「Redis 快取 + JWT + CRUD + Gateway」，加入 Webhook/ERP 到 gateway
  - MVP 定義加入 simpleec-gateway
  - 路線圖 + 時間估算 18→19
- §11 技術決策：新增「API 角色」分開部署決策 + 前端 API / 對外 API 兩行
- §0 缺口表：新增「對外 API（simpleec-gateway）」行，Webhook/ERP 標註 simpleec-gateway

## 第七輪修正（1 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 36 | **fast topic 的失敗一律不重打（鐵則）**：庫存、價格、出貨確認、上下架都是 fast，失敗了就是失敗了。快慢分離本質上是分散式 CAP 理論的商務應用：fast=AP（要快/失敗算了）、slow=CP（可等/可重試）。這是商務判斷不是程式判斷 | §3.2, §3.3, §3.9, §5.2, §5.3.5（設計+Java code）, §11 |

## 第七輪修改的內容
- §3.2 快慢分離：新增 CAP 理論商務應用說明，fast 標註「失敗不重打」，slow 標註「失敗可重打（有條件）」
- §3.3 Topic 設計：task.failed 描述改為「★ fast 一律不重打，slow 依條件重打」
- §3.9 失敗處理：
  - 新增「★ 鐵則：fast topic 的失敗一律不重打」區塊（CAP 商務應用說明）
  - RetryDispatchJob 流程新增 step 0（fast 鐵則第一關）
  - 底部新增 ★ fast 永遠不重打鐵則提醒
- §5.2 JOB 總覽表：RetryDispatchJob 描述改為「★ fast 一律不重打；slow 依條件判斷」
- §5.3.5 RetryDispatchJob 設計：
  - 職責新增「★ 鐵則：fast topic 的失敗一律不重打」
  - 流程新增 step 0（originalTopic .fast → NOT_RETRYABLE_FAST → 寫 DB LOG）
  - 底部新增鐵則提醒（商務判斷 CAP AP 取向）
- §5.3.5 RetryDispatchJob Java code：handle() 方法開頭新增 fast topic 判斷（step 0），`endsWith(".fast")` → save NOT_RETRYABLE_FAST → ack return
- §11 技術決策：新增「fast 不重打」決策行（CAP 理論商務應用）

## 第八輪修正（商務流程走讀 + 9 項缺口補齊）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 37 | **daily_statistics 預彙整表**：不即時查 orders（對 DB 壓力極大），每天以 merchant 時區為準寫一筆統計（per channel + 彙總）。前端選區間時查 daily_statistics 表，最小單位=一天 | §1.1, §1.3, §5.3.4, §6.2, §11 |
| 38 | **FetchProductsActionService 商品同步**：前端按鈕 → slow topic → 打通路 API → upsert sell_pack + 自動建 product（SKU match）。訂單定位用 channel_product_id + channel_spec_id → sell_pack → product | §5.3.1, §5.4 |
| 39 | **DailyStatisticsActionService**：BackendJob 新增 DAILY_STATISTICS action，用 merchant 時區算 UTC 起訖查 orders 表，UPSERT daily_statistics | §5.3.4 |
| 40 | **SchedulerJob 時區感知**：DAILY_STATISTICS 分發依 merchant.user_local_time_zone 判斷本地午夜，不靠系統 crontab | §6.2 |
| 41 | **API 擴充**：新增 sync-products、statistics 3 端點（summary/daily/by-channel）、channel health | §2.4 |
| 42 | **DashboardView 首頁**：統計卡片 + 每日趨勢折線圖 + 通路健康狀態表。預設首頁改為 /dashboard | §8.3, §8.6 |
| 43 | **simpleec-admin-api 獨立**：業務後台（開商家帳號）跟前台 API 分開，非 MVP 範圍 | §2.1, §0 |
| 44 | **§5.4 路由表更新**：新增 FetchProductsActionService + DailyStatisticsActionService 路由，補充 fast/slow 失敗處理鐵則 | §5.4 |
| 45 | **§12 商務流程走讀**：新增端到端流程走讀（簽約→設通路→拉單→同步商品→統計→Dashboard），每步對照設計章節 | §12（新增） |

## 第八輪修改的內容
- §0 缺口表：新增「對外 API（simpleec-gateway）」、「Webhook 接收（simpleec-gateway）」、「ERP 串接 API（simpleec-gateway）」
- §1.1 表關係：新增 daily_statistics + failed_task_logs
- §1.3 需新增的表：新增 daily_statistics 完整 DDL（含 UNIQUE + INDEX）+ failed_task_logs DDL
- §2.1 系統角色：新增 simpleec-admin-api（業務後台 API，非 MVP）說明
- §2.4 API 端點清單：新增 POST sync-products、GET statistics/summary、GET statistics/daily、GET statistics/by-channel、GET channels/health + Nginx 路由規則
- §5.3.1 ChannelJob：新增 FETCH_PRODUCTS slow action + FetchProductsActionService 完整邏輯（upsert sell_pack + 自動建 product by SKU） + 訂單定位說明
- §5.3.4 BackendJob：新增 DAILY_STATISTICS action + DailyStatisticsActionService（時區感知 UTC 起訖 + per channel 彙整 + UPSERT）
- §5.4 自治路由表：新增 FetchProductsActionService（無下游）+ DAILY_STATISTICS（無下游）+ 失敗處理鐵則說明
- §6.2 SchedulerJob：新增 DAILY_STATISTICS 分發 case（逐商家判斷 localNow 是否為午夜 00:05-00:10）
- §8.3 頁面設計：新增 DashboardView 完整 wireframe（統計卡片 + 趨勢圖 + 通路健康表）+ 通路設定 Dialog「同步商品」按鈕
- §8.6 路由：/ → /dashboard（預設首頁）
- §11 技術決策：新增 4 行（統計資料預彙整、統計時區、商品同步、後台 API）
- §12 商務流程走讀（全新章節）：Step 1-6 端到端流程 + 流程總結圖

## 第九輪修正（2 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 46 | **FETCH_PRODUCTS partition key = channelId**：同步商品必須排隊，同一通路不能並行跑兩次同步（按鈕連點/排程與手動撞→第二次排在第一次後面） | §3.4, §2.4, §5.4, §12 Step 4 |
| 47 | **通路健康度三層判斷**：不是只有正常/異常。🟢 OK = API+Token 暢通、🟡 TOKEN_INVALID = API 活著但 Token 不對（要重新授權）、🔴 API_DOWN = 通路 API 掛了（等平台恢復）。ChannelJob 每次操作後寫 health 到 channel_sync_logs | §2.4, §3.9, §8.3, §12 Step 6 |

## 第九輪修改的內容
- §3.4 Partition Key 策略表：新增「同步商品」行（key=channelId，理由：同通路排隊不能並行）+ 新增 Q&A 說明
- §2.4 API：sync-products 備註加 key=channelId 排隊；channels/health 改為三層判斷說明
- §3.9 失敗處理：失敗分支改為判斷失敗類型 → health=API_DOWN / TOKEN_INVALID / OK
- §5.4 路由表：FetchProductsActionService 加 partition key=channelId 說明
- §8.3 DashboardView wireframe：通路健康表改為三欄（健康度 + 原因），底部改為三層圖例
- §8.3 備註：改為三層判斷說明（API_DOWN / TOKEN_INVALID / OK）+ 查 channel_sync_logs 最近一筆
- §12 Step 4：TaskMessage 加 key=channelId 排隊說明
- §12 Step 6 Dashboard：健康狀態改為三層範例（暢通/Token過期/API無回應）
- §12 流程總結：通路健康改為三層說明

## 第十輪修正（2 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 48 | **CHECK_HEALTH 獨立 fast action**：通路健康檢查是獨立的 ChannelJob fast action，不是附帶在其他操作裡的副作用。SchedulerJob 每 10 分鐘觸發，三層結果（OK / TOKEN_INVALID / API_DOWN）必須寫 channel_sync_logs 記錄 | §1.1, §1.3, §3.4, §3.9, §5.3.1, §5.4, §6.2 dispatch + yml, §7.4, §8.3, §11 |
| 49 | **DB Partition 自動管理**：時序表（orders, channel_sync_logs, daily_statistics 等 7 張）需做 PARTITION BY RANGE。BackendJob 新增 MANAGE_PARTITIONS action，SchedulerJob 每天觸發一次，自動建立未來 2 個月 partition + 可清理舊 partition | §1.4（新增）, §5.3.4, §5.4, §6.2 dispatch + yml, §11 |

## 第十輪修改的內容
- §1.1 表關係：channel_sync_logs 備註加「需加 health 欄位」
- §1.3 需新增的表：新增 ALTER TABLE channel_sync_logs ADD COLUMN health + INDEX
- §1.4 DB Partition 設計（全新章節）：7 張需 partition 的表、命名規則、DDL 範例、BackendJob 自動管理說明
- §3.4 Partition Key 策略表：新增「健康檢查」行（key=channelId）
- §3.9 失敗處理：通路健康改為 ★ 獨立 CHECK_HEALTH action 說明（不附帶在其他操作失敗裡）
- §5.3.1 ChannelJob action 清單：fast 區新增 CHECK_HEALTH → CheckHealthActionService
- §5.3.1 ChannelJob 新增 CheckHealthActionService 完整邏輯（3 步 + 三層判斷 + 寫 LOG）
- §5.3.4 BackendJob action 清單：新增 MANAGE_PARTITIONS
- §5.3.4 BackendJob 新增 ManagePartitionsActionService 完整邏輯（查 pg_catalog → CREATE PARTITION IF NOT EXISTS → 清理舊 partition）
- §5.4 路由表：新增 CheckHealthActionService（無下游，寫 LOG）+ MANAGE_PARTITIONS（無下游）
- §6.2 SchedulerJob dispatch：新增 CHECK_ALL_HEALTH case + MANAGE_PARTITIONS case
- §6.3 yml 排程規則：新增 check-health（每 10 分鐘）+ manage-partitions（每天 UTC 04:00）
- §7.4 通路任務粒度：小任務新增 CHECK_HEALTH
- §8.3 DashboardView 備註：改為「★ 由獨立 CHECK_HEALTH action 檢查」
- §11 技術決策：新增 2 行（通路健康檢查獨立 action、DB Partition 自動管理）

## 第十一輪修正（1 點）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 50 | **先平台再通路**：資料表階層是 merchant → channel_setting（平台：API 憑證）→ channel（通路：具體賣場）。一個 channel_setting 可有多個 channel（如 momo 平台下有 momo冷凍 + momo一般）。API 和 UI 都要反映這個階層 | §1.1, §2.3.1, §2.4, §8.3, §12 Step 2 |

## 第十一輪修改的內容
- §1.1 表關係概覽：修正為 `merchant → channel_setting (平台) → channel (通路)`，加★說明「先平台再通路」
- §2.3.1 Controller：拆成 ChannelSettingController（平台 CRUD）+ ChannelController（通路 CRUD + activate + sync-products）
- §2.4 API 端點清單：新增「平台（channel_setting）」區塊（5 個端點），通路區塊改為 6 個端點（含 activate）
- §8.3 ChannelListView wireframe：改為雙層展開結構（平台展開 → 通路列表），平台設定 Dialog + 通路設定 Dialog 分開
- §8.3 排程狀態 Tab：標題改為「平台設定 Dialog Tab」
- §12 Step 2：改為三步驟（1. 新增平台 → 2. 在平台下新增通路 → 3. 啟動通路）+ 階層關係圖

## 第十二輪 — 全文整理（cleanup）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 51 | **全文整理**：移除 4 個 stale `← NEW` 標記。全文掃描確認：命名一致性（channel_setting=平台、channel=通路）✓、container 數量全文 19 ✓、章節編號 §0–§12 連續 ✓、交叉引用全部有效 ✓、Kafka topic 13 個 ✓、JOB 6 支 ✓、無殘留 simpleec 命名 ✓ | §5.4 |

## 全文整理掃描結果
- ✅ 命名一致：channel_setting（平台）→ channel（通路）全文一致
- ✅ Container 計數：19 個，全文 8 處引用全部一致
- ✅ 章節編號：§0–§12 連續，子章節（§1.1–1.4, §3.1–3.10, §5.3.1–5.3.5）全部正確
- ✅ 交叉引用：所有「見 §x.x」引用驗證通過，無斷鏈
- ✅ Kafka Topics：13 個（8 channel fast/slow + order.process + scheduler + task.backend/frontend/failed）
- ✅ JOB 數量：6 支（ChannelJob, OrderProcessJob, SchedulerJob, BackendJob, FrontendJob, RetryDispatchJob）
- ✅ 無殘留 simpleec 命名（全部 simpleec）
- ✅ 無 channel_type 與 channel_setting_id 衝突
- 🔧 移除：4 個 `← NEW` 殘留標記（§5.4 路由表）

## 第十三輪 — 統一命名 channel_setting → platform — 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 52 | **全域改名 channel_setting → platform**：表名、Controller 名、API 路徑、Redis key、快取名、UI 標籤、§12 流程走讀全部統一 | §1.1, §2.3.1, §2.4, §3.9 Redis, §5.3.1 cache config, §5.4 DB 讀取, §8.3 UI wireframe, §12 Step 2 |

### 改名對照

| 原名 | 新名 | 位置 |
|------|------|------|
| `channel_setting`（表/概念） | `platform` | 全文 |
| `ChannelSettingController` | `PlatformController` | §2.3.1 |
| `/api/v1/channel-settings` | `/api/v1/platforms` | §2.3.1, §2.4, §12 |
| `channelSettingId` | `platformId` | §2.3.1, §2.4, §12 |
| `channel:setting:all` / `List<ChannelSetting>` | `platform:all` / `List<Platform>` | §3.9 Redis |
| `"channelSettings"` (cache name) | `"platforms"` | §5.3.1 cache config |
| `channels + channel_setting` | `channels + platform` | §5.4 DB 讀取 |
| UI wireframe `（channel_setting）` | `（platform）` | §8.3 |

> 唯一保留的 `channel_setting` 出現在 §1.1 歷史註記：「舊表名 channel_setting → 已改名 platform」

## 第十三輪補充 — DB Schema 表名也改成 platform — 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 52b | **DB schema 全域改名 channel_setting → platform**：SQL 表名、欄名（channel_setting_id → platform_id, channel_setting_name → platform_name）、FK constraint 名、COMMENT、INSERT seed data 全部統一 | 01-original-schema.sql, 02-new-tables.sql |

### 改名對照（SQL 檔案）

| 原名 | 新名 | 檔案 |
|------|------|------|
| `CREATE TABLE channel_setting` | `CREATE TABLE platform` | 01-original-schema.sql |
| `channel_setting_id` (PK + 所有 FK 欄位) | `platform_id` | 兩個 SQL 檔案 |
| `channel_setting_name` | `platform_name` | 01-original-schema.sql, 02-new-tables.sql |
| `REFERENCES public.channel_setting` | `REFERENCES public.platform` | 兩個 SQL 檔案 |
| `INSERT INTO public.channel_setting` | `INSERT INTO public.platform` | 兩個 SQL 檔案 |
| `COMMENT ON TABLE/COLUMN public.channel_setting` | `public.platform` | 01-original-schema.sql |
| `fk_api_version_channel_setting` (constraint) | `fk_api_version_platform` | 02-new-tables.sql |
| COMMENT '通路' / '通路名稱' / '通路幣別' (platform 表) | '平台' / '平台名稱' / '平台幣別' | 01-original-schema.sql |

> 不動的檔案：DESIGN.md（舊版）、REWRITE_PLAN.md 歷史記錄中的 channel_setting 引用

## 第十四輪 — 憑證分層：platform=credential, channel=token — 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 53 | **platform 存第三方認證（credential1~N 抽象欄位），token 存在 channel（各館授權）**：之前 token 混在 platform 描述裡是錯的。platform 表新增 credential1~2（先 2 欄後續可擴充，各平台認證資料不同）；DESIGN_v2.md 全文修正描述、wireframe Token 從平台 Dialog 搬到通路 Dialog | 01-original-schema.sql, DESIGN_v2.md: §1.1, §2.3.1, §2.4, §5.3.1, §8.3, §12 |

### 憑證分層原則

| 層級 | 存什麼 | 設計 | 用途 |
|------|--------|------|------|
| **platform** | `credential1` + `credential2`（可擴充） | 抽象化，不綁死語意 | 各平台第三方認證（所需資料不一定一樣） |
| **channel** | `token` ~ `token5` | 同 channel 表原有設計 | 各館/賣場的獨立授權 token |

> ★ 與 channel 的 token1~5 同風格 — 泛用欄位，COMMENT 標註常見用途但不限定

### 改動明細

**SQL（01-original-schema.sql）：**
- platform 表新增 `credential1 VARCHAR(4096)` + `credential2 VARCHAR(4096)` 欄位
- 新增 COMMENT：'平台認證欄位1（通常為 API Key，各平台用途不同）'、'平台認證欄位2（通常為 API Secret，各平台用途不同）'

**DESIGN_v2.md：**
- §1.1 表關係：platform 描述改為「第三方認證」，channel 描述改為「授權 token + 賣場設定」
- §1.1 ★ 說明：platform = credential1~N（各平台所需不同，先 2 欄可擴充），channel = token1~token5
- §2.3.1 PlatformController：GET/{id} 改為「credential 脫敏」，PUT 改為「credential1~N」
- §2.3.1 ChannelController：GET/{id} 加「token 脫敏」，POST 加「含 token」，PUT 加「token1~token5」
- §2.4 API：platform 端點 → credential 脫敏；channel 端點 → 加 token 相關描述
- §5.3.1 ActionService 4 步：步驟 2 改為「組合認證憑證（platform credential + channel token）」
- §5.3.1 DB 讀取：拆成 platform（credential1~N）+ channels（token + 設定）兩行
- §5.3.1 CheckHealth + Java code + Resource DTO：全部改為「platform credential + channel token」
- §8.3 wireframe：平台 Dialog 改為「認證1/認證2（各平台欄位數不同，可擴充）」；Token 在通路 Dialog
- §8.3 平台 Dialog Tab：移除 [Token] tab，只剩 [基本設定] [排程狀態]
- §12 Step 1：改為「填入第三方認證資料（credential1~N）」
- §12 Step 2：新增「填入該館的授權 token」描述

## 第十四輪補充 — credential 抽象化 + §10 更新 + Skill 封裝 — 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 53b | **credential 抽象化**：`api_key` + `api_secret` 改為 `credential1` + `credential2`（VARCHAR(4096)），與 channel 的 token1~5 同風格，各平台所需認證資料不同，先 2 欄後續可擴充 | 01-original-schema.sql, DESIGN_v2.md 全文 |
| 54 | **§10 開發順序更新**：補入後續幾輪新增的設計（CheckHealth、DailyStatistics、ManagePartitions、PlatformController、FetchProducts、DashboardView） | §10 Phase 0/2/3/4/5/6 |
| 55 | **Skill 封裝**：建立 `simpleec-oms` plugin + `simpleec-oms-design` skill，封裝本次全部電商技術溝通重點（架構、Pattern、規則、API、開發時程） | ~/.claude/plugins/local/simpleec-oms/ |

### §10 更新明細
- Phase 0：新增「確認 SQL platform 有 credential1~2，channel 有 token1~token5」
- Phase 2：新增 CheckHealthActionService（獨立健康檢查，三層判斷）
- Phase 3：新增 DailyStatisticsActionService + ManagePartitionsActionService
- Phase 4：新增 PlatformController（CRUD + credential 脫敏）；ChannelController 改為含 token 脫敏 + activate + sync-products
- Phase 5：新增 FetchProductsActionService（同步商品）
- Phase 6：新增 DashboardView（統計卡片 + 趨勢圖 + 通路健康表）；通路管理頁改為雙層結構

### Skill 檔案
- Plugin: `/home/tom/.claude/plugins/local/simpleec-oms/.claude-plugin/plugin.json`
- Skill: `/home/tom/.claude/plugins/local/simpleec-oms/skills/simpleec-oms-design/SKILL.md`
- 觸發關鍵字：SimpleEC, OMS, 通路, 平台, 拉單, 訂單管理, 電商, channel job, simpleec-oms

## 第十五輪 — Entity ↔ Schema 對齊（Level 1）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 56 | **所有 Entity PK Long→String NanoID**：4 個 Entity (Order, Product, SellPack, OrderStatusLog) 的 id + FK 全改 String + IdType.ASSIGN_UUID。刪除 OrderItem.java + ProductSpec.java + 對應 Mapper | Entity, Service, Controller, ChannelAdapter |

## 第十六輪 — PII 加密 + API 遮罩（Level 1.5）— 已完成

| # | 修正重點 | 影響範圍 |
|---|---------|---------|
| 57 | **PII 欄位 AES-256-GCM 加密**：orders 表 4 個買家欄位（buyer_name, buyer_phone, buyer_email, shipping_address）透過 MyBatis TypeHandler 透明加解密。Master key 三次 Base64 存 global_config，PBKDF2 + merchantId 衍生 per-merchant key | core/crypto/*, Order.java, OrderService.java, 01-schema.sql, 02-seed-data.sql |
| 58 | **API PII 遮罩 + 解鎖 + CSV 匯出**：列表 API 回傳 OrderVO（遮罩：王\*明、0912\*\*\*678）；GET /{id} 回傳完整明文；GET /export CSV 下載含完整明文 + UTF-8 BOM for Excel | common/util/PiiMasker.java, api/vo/OrderVO.java, OrderController.java, OrderService.java |
