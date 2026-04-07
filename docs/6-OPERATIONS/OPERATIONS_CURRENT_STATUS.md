# SimpleEC OMS - 当前运维状态 (Apr 7, 2026)

## 📊 系統狀態：✅ 完全運行 (All Systems Operational) — 2026-04-07 更新（Platform Capabilities + Channel Health）

### 核心系统
| 组件 | 状态 | 备注 |
|------|------|------|
| **PostgreSQL 数据库** | ✅ 运行 | port 5433 |
| **Redis 缓存** | ✅ 运行 | port 6379 |
| **Kafka 事件流** | ✅ 运行 | port 9092 (21 topics) |
| **Spring Boot API** | ✅ 运行 | port 8080 → 8082 (via Nginx) |
| **User Frontend** | ✅ 运行 | port 5173 → 8089 (via Nginx) |
| **Admin Frontend** | ✅ 运行 | port 8084 → 8089 (via Nginx) |
| **Nginx 反向代理** | ✅ 运行 | port 8089 |
| **Monitoring (Grafana)** | ✅ 运行 | port 3000 |

### API 端点健康状态

**公开端点**（无认证）：
- ✅ `GET /api/health` → 200
- ✅ `GET /api/version` → 200

**认证端点**（基于JWT）：
- ✅ `POST /api/auth/login` → 200 (返回JWT token)
- ✅ `GET /api/auth/me` → 200 (获取当前用户)

**管理端点**（需要认证）：
- ✅ `GET /api/admin/account` → 200
- ✅ `GET /api/admin/platform` → 200
- ✅ `GET /api/admin/merchant` → 200

**用户端点**（完整的用户事件流，需要认证）：
- ✅ `GET /api/user/channels` → 200
- ✅ `GET /api/user/products` → 200
- ✅ `GET /api/user/orders` → 200
- ✅ `GET /api/user/sellpacks` → 200
- ✅ `GET /api/user/refunds` → 200
- ✅ `GET /api/user/settings` → 200

### 新增公开端点
- ✅ `GET /api/enums/order-statuses` → 200 (返回所有订单状态)
- ✅ `GET /callback/shopee` → Shopee OAuth callback（公開，無 JWT）

### Shopee OAuth 端點（需 JWT）
- ✅ `GET /api/user/channels/{id}/shopee/auth-url` → 生成授權 URL
- ✅ `POST /api/user/channels/{id}/shopee/refresh-token` → 手動強制刷新
- ✅ `POST /api/user/channels/{id}/shopee/disconnect` → 斷開授權

---

## 🔧 Platform Capabilities UI + Channel Health Overview (2026-04-07)

### commit: e353a07

**功能：Platform 管理完整 capabilities 設定 + Channel 健康狀態總覽**

#### Platform 管理修正（admin-app）
- **types.ts** — Platform interface 與 DB 欄位 1:1 對齊
  - 移除：`platform_code`, `merchant_id`, `api_key`, `api_secret`, `status`
  - 新增：`credential1`, `credential2`, `actived`, `queue_topic`, `currency`, `ship_options`, `capabilities`
- **PlatformForm.vue** — 大幅擴充表單功能
  - OAuth 類型選擇（下拉選單：無 / Shopee OAuth）
  - 平台能力勾選（multiLocation, webhook, asyncInventory）
  - Token 欄位名稱自訂（token1~token5 可自訂 label）
  - 配送選項 JSON 編輯器（textarea）
- **PlatformTable.vue** — 表格欄位擴充
  - 新增「配送選項」欄位（顯示 JSON keys）
  - 新增「平台能力」欄位（el-tag 顯示）
  - 新增「最後更新」時間欄位
- **Backend** — `AdminPlatformController.updatePlatform()` 新增 capabilities 更新邏輯
- **JSONB 映射修復** — Platform entity 改用 `@JdbcTypeCode(SqlTypes.JSON)`，修正 PostgreSQL JSONB 類型錯誤

#### Channel 管理擴充（user-app）
- **ChannelPage.vue** — 大幅擴充
  - Token1~5 永遠顯示（不再因無 alias 而隱藏）
  - 新增 `🔑 認證憑證` 分隔線和提示文字
  - 編輯模式 placeholder 改為「留空表示不修改」
  - Dialog 加入 `:key` 確保編輯時重新渲染
  - 新增同步日誌 Drawer（分頁查詢 syncType/狀態/健康/HTTP/錯誤/時間）
  - 新增「日誌」按鈕到每個通路卡片
- **ChannelHealthOverview.vue**（新元件）— 通路健康狀態總覽
  - 顯示所有通路的健康狀態表格
  - 支援手動重新整理
- **channel.ts** — 新增 `getSyncLogs()` API 方法

#### Backend 擴充
- **UserChannelController.java** — 新增同步日誌 API + 健康狀態快取
  - `GET /api/user/channels/{id}/sync-logs`（分頁查詢）
  - Redis 快取 `channel:health:` prefix，TTL 10 分鐘
- **ChannelSyncLogRepository.java** — 新增分頁查詢方法
- **HealthCheckService.java** — Redis 快取健康檢查結果，健康日誌加入 `health` 欄位

#### Nginx 動態 DNS 解析
- **nginx.conf** — 移除靜態 `upstream` 區塊
  - 改用 `resolver 127.0.0.11 valid=10s` + `set $var` 動態解析
  - 修正容器重啟 IP 改變時的 502 Bad Gateway 問題

#### 設計要點
- **capabilities JSONB**：驅動業務邏輯，避免 hardcode 平台名稱判斷
  - `oauthFlow`: 決定 Channel 頁面是否顯示 OAuth 按鈕
  - `tokenLabels`: 定義 token1~5 的顯示名稱
  - `multiLocation/webhook/asyncInventory`: 平台能力旗標
- **Token 安全**：編輯時不顯示完整 token，留空表示不修改
- **健康檢查快取**：Redis 10 分鐘 TTL，避免頻繁檢查外部 API

---

## 🔧 Shopee OAuth Token 管理 (2026-04-06)

### commit: a740d9e

**功能：Shopee OAuth 2.0 授權流程 + Token 生命週期管理**

#### 新增元件
- `ChannelVO` — Channel API 回傳改用 VO，token1~5 遮罩顯示（前4...後4），含 oauthStatus
- `ShopeeOAuthService` — auth URL 生成（state→Redis）/ code exchange / refreshWithLock / disconnect
- `ShopeeCallbackController` — public GET /callback/shopee，postMessage 關小視窗
- `ShopeeTokenRefreshHandler` — BackendJob，每小時掃 expires_at < now+90min 的 Shopee channel
- `SchedulerEventHandler` — minuteOfHour==0 新增 SHOPEE_TOKEN_REFRESH dispatch

#### 設計要點
- **Token 欄位語意**：token=access_token (4h), token2=refresh_token (30d), token3=shop_id, token4=expires_at
- **分散式鎖**：Redis key `shopee:token:refresh:lock:{channelId}` TTL 30s，多 instance 不並發刷新同一 channel
- **Popup 流程**：前端 `window.open` 小視窗，callback 後 `postMessage` 通知主頁面，不離開 Channel 頁面
- **oauthStatus**：NOT_CONNECTED / CONNECTED / EXPIRING_SOON（≤60min）/ EXPIRED
- **手動填 token** 仍保留（PUT /api/user/channels/{id} 的 token1~5）供非 OAuth 平台使用

#### 文件
詳見 `docs/7-IMPLEMENTATION/SHOPEE_OAUTH_GUIDE.md`

---

## 🔧 全面審查 Round 2 — 擴充性 + 水平擴展修復 (2026-04-06)

### commit: 65b81de

#### 核心架構修復

**ARCH-1: ChannelAdapterRegistry（擴充性）**
- 新增 `simpleec-channel/.../registry/ChannelAdapterRegistry.java`
- 自動收集所有 `ChannelAdapter` Spring Bean，依 `platformCode` 路由
- 所有 outbound handler（SHIP_ORDER / UPDATE_PRICE / UPDATE_INVENTORY / APPROVE_RETURN / REJECT_RETURN / FETCH_RETURNS）改用 registry，不再 hardcode `cyberbizAdapter`
- 新增平台時只需實作 `ChannelAdapter`，無需修改任何 handler

**CyberbizAdapter Credential Race Condition（水平擴展）**
- 移除 `private String token, secret` singleton instance fields
- 改用 `ConcurrentHashMap<channelId, String[]>` 儲存 per-channel credentials
- `setCredentials(channelId, token, secret)` 原子寫入，多 thread / 多 channel 不互蓋
- `ChannelAdapter` 介面簽名同步更新：`setCredentials(String channelId, String token, String secret)`
- 補充 `fetchProducts(String channelId)` 至介面（ARCH-5）
- `SyncPackChannelHandler` 改用 `ChannelAdapter` 介面而非具體 class（ARCH-20）

#### Kafka 契約修復

| 代號 | 問題 | 修復 |
|------|------|------|
| K-1 | FETCH_ORDER_DETAIL header 缺 requestId/platformId/source | 補齊；timestamp 改用 baseTimestamp |
| K-4 | TaskFrontendListener 沒有 schema version 驗證 | 補 SchemaVersionHandler.validate() + DLT routing |
| K-5 | OrderUpsertConsumer DLT 送 JsonNode 而非 String | 改送 messageJson |
| K-8 | FETCH_ORDERS 用 requestId 當 key，同 channel 可能散落不同 partition | 改用 channelId |
| K-11 | HeartbeatJob body 重複放 timestamp | 移除（header 已有） |

#### 資料完整性
- **DB-14**: 移除 `ReturnOrderRepository.findByChannelRefundId()` 無 scope 方法（多租戶安全）

#### E2E 驗證（2026-04-06）
- 12 個 API 端點全部通過
- Cyberbiz 即時資料流確認（5 筆訂單進 DB，含買家/商品/地址）
- 事件流完整：HeartbeatJob → scheduler → FETCH_ORDERS → FETCH_ORDER_DETAIL → ORDER_UPSERT → DB

---

## 🔧 Stats Pipeline 修復 + Test Seeder (Mar 27, 2026 — 第二輪)

### 新增功能
- **`POST /api/user/orders`** — 接收訂單並發布 `ORDER_UPSERT` 到 Kafka，走完整事件流（不直接寫 DB）
- **`docker/test-data-generator/`** — Python seeder，透過 API 打假訂單驗證端對端資料流

### Bug Fix（共 20+ 個問題，三輪 Code Review）
- `OrderUpsertConsumer` — `.get()` 改 `.path()` 防 NPE 連鎖，移除 unused import/param
- `ReturnUpsertConsumer` — 補 stats dirty marker 寫入，移除 unused import/param
- `DailyStatisticsService` — early return 時改為刪除過時 stats 列
- `ChannelJobConsumer` — FETCH_ORDER_DETAIL 的 `.get()` 改 `.path()`
- `RetryJobConsumer` — MissingNode cast ClassCastException 修復
- `OrderService` — NOT NULL 欄位（isRollback/hasRefund/orderStatus）加 null 守衛
- `01-schema.sql` — `daily_statistics.id` 加 NOT NULL；加 DEFAULT partition；enum 大小寫改大寫
- `02-seed-data.sql` — BCrypt hash 修正；訂單狀態改大寫；daily_statistics 欄位名稱修正
- `docker-compose.yml` — 所有 21 個 Java 容器加 `JAVA_TOOL_OPTIONS` 記憶體限制

---

## 🔧 全面審查修復 — Batch 1+2 (Mar 27, 2026)

### 修復範圍：P0 全部 7 個 + P1-3 至 P1-7（共 12 個問題）

#### P0-1: return.process Kafka topic 補建
- `docker/init-kafka/create-topics.sh` — 加入 `return.process`（3 partitions，與 order.process 一致）

#### P0-2: FetchReturnsHandler 補實作
- 新增 `simpleec-channel-job/.../handler/FetchReturnsHandler.java` — stub 實作，防止 FETCH_RETURNS 被靜默丟棄
- `ChannelJobConsumer.java` — 注入並加入路由分支

#### P0-3: Order Entity column-length 修正
- `simpleec-core/.../entity/Order.java` — `order_status` 和 `channel_id` 的 `length=50` 改為 `length=20`（對齊 SQL VARCHAR(20)）

#### P0-4: simpleec-gateway 加入 docker-compose
- `docker/docker-compose.yml` — 新增 `simpleec-gateway` 服務（port 8081，依賴 kafka-init）

#### P0-5: ENCRYPTION_MASTER_KEY 注入
- `docker/docker-compose.yml` — `simpleec-order-job` 和 `simpleec-api` 加入 `ENCRYPTION_MASTER_KEY: ${ENCRYPTION_MASTER_KEY:}`

#### P0-6: RetryJobConsumer errorInfo wrapping 修正
- `OrderUpsertConsumer.java` 和 `ReturnUpsertConsumer.java` — catch block 改為包裝 `errorInfo`（errorType: SERVER_ERROR_5XX）再送 task.failed，重試機制恢復正常

#### P0-7: application.yml hardcoded localhost 修正（4 個模組）
- `simpleec-api` — DB `localhost:5433` → `${DB_HOST:simpleec-postgres}:${DB_PORT:5432}`；Kafka → `${KAFKA_BOOTSTRAP_SERVERS:simpleec-kafka:9092}`
- `simpleec-scheduler-job` — DB/Redis localhost → env vars
- `simpleec-backend-job` — DB localhost → env var
- `simpleec-retry-job` — DB localhost → env var

#### P1-3: admin-app API response interceptor 修正
- `admin-app/src/api/index.ts` — 改為 `response.data.data ?? response.data`，避免在 AdminApiResponse<Void> 時 crash

#### P1-4: DailyStatisticsService.recalculate() refundCount 修正
- `DailyStatisticsService.java` — 注入 `ReturnOrderRepository`，`refundCount` 改為從 `refund_orders` 實際計算
- `ReturnOrderRepository.java` — 新增 `countByMerchantIdAndChannelIdAndStatDate()` native query

#### P1-5: ChannelSyncLog Entity 補齊 request_payload / response_payload
- `simpleec-channel-job/.../ChannelSyncLog.java` 和 `simpleec-api/.../ChannelSyncLog.java` — 各自補加兩個 TEXT 欄位映射

#### P1-6: CANCEL_ORDER 路由修正
- `UserOrderController.java` — cancel action 改為發 `CANCEL_ORDER_INTERNAL` 到 `order.process`（原本錯誤地送到 `{platform}.fast`）

#### P1-7: sell_pack unique index NULL 修正
- `docker/init-db/01-schema.sql` — unique index 改用 `COALESCE(channel_spec_id, '')` 防止 NULL 重複

---

## 🔧 Wave 1 深度審查修復 (Mar 26, 2026)

### DB1 修復：channel_sync_logs 缺少 http_status 欄位
**問題**：`ChannelSyncLog` Entity 有 `httpStatus` 欄位，但 `01-schema.sql` 和 `SCHEMA.md` 均無此欄位，每次寫入都會拋出 DB 錯誤
**根本原因**：Schema 文件未與 Entity 代碼同步
**修復**：
- `docker/init-db/01-schema.sql` — 在 `channel_sync_logs` 加入 `http_status INTEGER`
- `docs/4-SCHEMA/SCHEMA.md` — 同步更新

### DB2 修復：sell_pack 唯一索引鍵錯誤
**問題**：`01-schema.sql` 的唯一索引使用 `(channel_id, channel_spec_id, sku)`，但 Repository 和 Handler 都以 `(channel_id, channel_product_id, channel_spec_id)` 作為 upsert 鍵
**根本原因**：Schema 文件與代碼邏輯不一致
**修復**：`01-schema.sql` 唯一索引改為 `(channel_id, channel_product_id, channel_spec_id)`

### K1 修復：FETCH_ORDERS/FETCH_ORDER_DETAIL retry 路由到錯誤 topic
**問題**：`RetryJobConsumer.routeTaskToTopic()` 把 `FETCH_ORDERS` 和 `FETCH_ORDER_DETAIL` 路由到 `order.process`，但這些是 channel job 任務，應重試到 `{platform}.slow`
**根本原因**：retry 路由邏輯未考慮 platform-specific topics
**修復**：
- `routeTaskToTopic()` 現在接受 `platformId` 參數
- Channel 任務（FETCH_ORDERS/FETCH_ORDER_DETAIL/FETCH_RETURNS/FETCH_RETURN_DETAIL）→ `{platform}.slow`
- Channel fast 任務（SHIP_ORDER/UPDATE_INVENTORY/UPDATE_PRICE/APPROVE_RETURN）→ `{platform}.fast`
- `RetrySchedulerJob` 同步更新，從 header 讀取 `platformId`

### K2 修復：FETCH_RETURNS 從未被 Scheduler 派發
**問題**：`SchedulerEventHandler.dispatchFetchOrders()` 只派發 `FETCH_ORDERS`，`FETCH_RETURNS` 被完全遺漏，導致退貨同步中斷
**根本原因**：`dispatchFetchOrders()` 方法未包含 FETCH_RETURNS 邏輯
**修復**：
- 新增 `dispatchFetchReturns()` 方法
- 每 5 分鐘 (% 5 == 0) 同時派發 `FETCH_ORDERS` 和 `FETCH_RETURNS`

### K3 修復：TaskBackendListener 無 DLT/failed 路由
**問題**：handler 拋出 exception 時，`TaskBackendListener` 只記錄 log，不進入 retry 流程，導致任何 backend 任務失敗都靜默消失
**根本原因**：異常處理代碼有 TODO 評論但未實作
**修復**：handler 失敗 → 發送到 `task.failed`（進入 retry 流程）；task.failed 發送失敗 → 直接到 `task.dlt`

### 文件整合
- `docs/3-EVENT-FLOW/event-flows/DB_ENTITY_GAPS.md` — 改為 REF stub，指向 `docs/4-SCHEMA/DB_ENTITY_GAPS.md`（正式文件）
- `docs/3-EVENT-FLOW/HANDLER_REGISTRY.md` — 更新 Scheduler 派發表，加入 FETCH_RETURNS，修正 STATS_RECALC 記錄
- `docs/4-SCHEMA/SCHEMA.md` — 同步 channel_sync_logs http_status 欄位

---

## 🔧 最近的关键修复和新功能 (Feb 24-25, 2026)

### 修复1：API 端点路由 (Commits 0ed6853, 7f594a7)
**问题**：所有端点返回 403/404 Forbidden/Not Found
**根本原因**：Spring 控制器 @RequestMapping 缺少 `/api` 前缀
**修复**：
- HealthController: `@RequestMapping("")` → `@RequestMapping("/api")`
- AuthController: `@RequestMapping("/auth")` → `@RequestMapping("/api/auth")`
- 9个用户/后端控制器：添加 `/api` 前缀

### 新功能1：动态订单状态选项 (Feb 25, 2026) ✨
**特性**：
- ✅ 后端 EnumController 提供公开 API 端点 `/api/enums/order-statuses`
- ✅ 前端 useOrderStatuses composable 自动获取并缓存状态
- ✅ Pinia store 管理全局状态（避免重复请求）
- ✅ 5个 Vue 组件（OrderTable, ShipmentTable, Dashboard, OrderPage, ShipmentPage）更新
- ✅ 所有硬编码的状态选项已替换为动态绑定
- ✅ App.vue 初始化时自动加载状态

**优势**：
- 后端 enum 修改时，前端自动同步（无需手动更新）
- 减少前后端重复定义状态
- 支持多语言（标签由后端定义）

**相关提交**：
- db51ae1: feat: Add label and description to OrderStatusEnum
- 99ae5b1: feat: Add /api/enums/order-statuses endpoint
- 3ea3220: feat: Allow public access to /api/enums/* endpoint

### 新功能2：Cyberbiz 订单流整合 (Feb 24-25, 2026) ✨
**特性**：
- ✅ ModeAOrderListHandler 添加 Kafka 回调确保消息送达
- ✅ OrderStatusMapper 支持 15+ 通路的状态转换
- ✅ 时间戳正确传递（Scheduler → Kafka → Handler）
- ✅ Cyberbiz 订单自动流入数据库（每 5 分钟同步一次）
- ✅ 状态统一为大写（PENDING, CONFIRMED 等）

**相关提交**：
- 7e14bfa: fix: Add Kafka send callback to ensure orders reach order.process topic
- 7220993: chore: Update user-app submodule with dynamic status options implementation

### 修复3：系统重启版本部署 (Commit 34d5805)
**问题**：重启后 API 运行旧版本代码（有路由错误）
**根本原因**：`start-on-boot.sh` 使用 `docker compose up -d` 无 `--build` 标志
**修复**：
```bash
# 改为：
docker compose build simpleec-api simpleec-user-app simpleec-admin-app
docker compose up -d
```
**用户需要手动执行**的额外修复：
- 编辑 `/etc/systemd/system/simpleec-oms.service`
- 查看：`docker/SYSTEMD_SERVICE_FIX.md`

---

## 📖 文档导航

### 快速参考
| 文档 | 用途 |
|------|------|
| **QUICK_START.md** | 3分钟快速开始 |
| **README.md** | 项目概述和基本说明 |
| **CLAUDE.md** | 用户指示和工作流指南 |

### 开发和部署
| 文档 | 用途 |
|------|------|
| **DEPLOYMENT_GUIDE.md** | 详细的部署步骤 |
| **docker/SYSTEMD_SERVICE_FIX.md** | 系统服务修复指南 |
| **CODE_STRUCTURE.md** | 代码结构说明 |

### 架构和设计
| 文档 | 用途 |
|------|------|
| **DESIGN_v2.md** | 完整的系统设计（224KB） |
| **PLATFORM_MAPPING.md** | 7个通路的状态映射和schema |
| **SCHEMA_ALIGNMENT_ACTUAL_DB.md** | Kafka消息与数据库对齐 |
| **EVENT_SAMPLES.md** | 事件流示例 |

### 操作和监控
| 文档 | 用途 |
|------|------|
| **OPERATIONS_CURRENT_STATUS.md** | 当前状态（本文件） |
| **KAFKA_QUICKSTART.md** | Kafka操作快速参考 |
| **NETWORK_ACCESS.md** | 网络和端口配置 |

### 待整理/过时文档（可考虑清理）
```
- DESIGN.md (旧版，用DESIGN_v2.md替代)
- 多个TASKS_*_*.md (任务完成后的记录)
- FINAL_VERIFICATION_REPORT_2026-02-21.md (旧报告)
- QA_REPORT_2026-02-21.md (旧报告)
- 多个README*.md (冗余)
```

---

## 🧪 验证系统健康状态

### 1. 检查容器状态
```bash
docker compose ps
# 应该看到所有31个容器运行
```

### 2. 测试API端点
```bash
# 登录获取JWT
TOKEN=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}' | jq -r '.token')

# 测试用户端点
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8082/api/user/products?page=0&pageSize=10

# 应返回: { "code": 200, "data": {...} }
```

### 3. 检查启动日志
```bash
tail -f /tmp/simpleec-startup.log  # 启动过程
tail -f /tmp/simpleec-sync.log     # 同步监控
docker logs simpleec-api           # API日志
```

### 4. 监控和告警
```bash
# Grafana 仪表盘
http://localhost:3000
Username: admin / Password: admin

# Kafka UI
http://localhost:8088

# Prometheus 指标
http://localhost:9090
```

---

## 🚨 已知问题（已解决）

### ✅ Kafka Consumer Coordinator Fix (Feb 24, 01:10 AM)
**问题**：Consumer groups 无法连接 Kafka coordinator，导致无法跟踪消费offset
- 错误：`TimeoutException: Call(callName=describeGroups(api=FIND_COORDINATOR), deadlineMs=...) timed out`
- 根本原因：`__consumer_offsets` topic 不存在（内部Kafka系统topic，不是自动创建的）
- **修复**：
  1. 手动创建 `__consumer_offsets` topic（50分区，cleanup.policy=compact）
  2. 更新 `docker/init-kafka/create-topics.sh` 确保自动创建此topic
  3. 添加 `easystore.fast` topic（缺失的平台）
  4. 添加 `task.dlt` topic（缺失的系统topic）
- **验证结果**：
  - ✅ 所有6个consumer groups 现在健康运行
  - ✅ 所有 groups 的 LAG = 0（完全同步）
  - ✅ Consumer offsets 正确跟踪

**Consumer Group Status（Feb 24, 01:10）**：
| Group | Status | Topics | Lag |
|-------|--------|--------|-----|
| frontend-job-group | ✅ 运行 | task.frontend | 0 |
| channel-job-group | ✅ 运行 | cyberbiz/easystore/shopee/shopify (slow) | 0 |
| scheduler-dispatcher-group-v4 | ✅ 运行 | scheduler | 0 |
| backend-consumer-group | ✅ 运行 | task.backend | 0 |
| dlt-consumer-group | ✅ 运行 | (DLT handling) | 0 |
| retry-job-group | ✅ 运行 | (Retry handling) | 0 |

---

## 📝 操作手册

### 系统重启
```bash
# 方式1：使用启动脚本（推荐，自动rebuild）
sudo reboot
# 或手动运行
bash start-on-boot.sh

# 方式2：使用systemd（需先手动更新service）
sudo systemctl restart simpleec-oms
```

### 查看日志
```bash
# 所有容器日志
docker compose logs -f

# 特定服务
docker logs -f simpleec-api
docker logs -f simpleec-kafka
docker logs -f simpleec-postgres
```

### 更新API代码
```bash
# 1. 编辑代码
# 2. 提交并推送
git add -A && git commit -m "..."
git push origin branch-name

# 3. 重建镜像
docker compose build simpleec-api

# 4. 重启服务
docker compose up -d simpleec-api

# 5. 验证
docker logs -f simpleec-api
```

### 清理资源
```bash
# 清理Docker镜像和容器
docker compose down -v  # 包括volume（谨慎！）
docker compose down     # 不删除volume

# 清理磁盘
bash docker/cleanup-disk.sh

# 检查磁盘使用
df -h
du -sh /home/tom/ONEEC/simpleec-oms/data/*
```

---

## ✅ 最后检查清单（重启后）

- [ ] 所有容器运行中：`docker compose ps | grep -c "Up"`
- [ ] API健康检查通过：`curl http://localhost:8082/api/health`
- [ ] 用户可以登录：`http://localhost:8089`
- [ ] Admin可以访问：`http://oms-admin.tomting.com` 或 `http://localhost:8089/admin/`
- [ ] Kafka topics创建：`docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh ...`
- [ ] Grafana仪表盘显示指标：`http://localhost:3000`

---

## 📞 常见问题排查

**Q: 重启后API报404错误？**
A: 检查是否rebuild了镜像。运行 `bash start-on-boot.sh` 或 `docker compose build simpleec-api`

**Q: 用户无法登录？**
A:
1. 检查数据库：`docker compose ps | grep postgres`
2. 检查API日志：`docker logs simpleec-api`
3. 验证密码：Test用户 `admin@a00000.com` / `pass123456`

**Q: Kafka topics 没有创建？**
A:
1. 检查Kafka状态：`docker logs simpleec-kafka | tail -20`
2. 手动创建（如果需要）：见 `KAFKA_QUICKSTART.md`

**Q: Nginx 返回502 Bad Gateway？**
A:
1. 检查后端服务是否运行：`docker compose ps | grep api`
2. 检查Nginx配置：`docker/nginx.conf`
3. 检查Nginx日志：`docker logs simpleec-nginx`

---

**最后更新**: Feb 24, 2026 12:50 AM
**维护者**: Tom
**分支**: `fix/admin-app-api-routing-and-nginx-proxy`

### 修复4：Nginx 配置和登入端口 (Feb 25, 2026)
**问题**：用户无法通过 `http://localhost:8080` 登入
**根本原因**：Nginx 监听 port **8089** 而不是 8080，且配置需要重新构建 Docker 镜像
**修复**：
```bash
# 重建 Nginx 容器
./quick-redeploy.sh simpleec-nginx
```
**正确的访问 URL**：
- 🟢 本地开发：`http://localhost:8089`（Nginx 代理）
- 🟢 生产环境：`https://oms.tomting.com`
- 🟡 后端直连（开发用）：`http://localhost:8082/api/...`

---

## 🔴 INCIDENT — 2026-04-06：前端程式碼遺失與全面 API 對接失效

**嚴重等級：CRITICAL（已解決）**
完整記錄：`docs/6-OPERATIONS/INCIDENT-2026-04-06-frontend-api-mismatch.md`

### 事件摘要
1. `user-app` 原始碼因 git submodule 未提交 `.gitmodules` 而遺失，未及時察覺
2. 恢復時未讀後端 controller 直接重建，登入路徑、URL、分頁、Response 格式全部寫錯
3. 審視團隊在部署後才啟動，用戶先看到問題

### 修復紀錄（2026-04-06 全日）

| 時間 | 修復內容 |
|------|---------|
| 上午 | 從 `dev:docs/archive/plans/` 恢復 22 個前端檔案 |
| 中午 | 修正 `/api/auth/login` 路徑、token localStorage key 統一為 `authToken` |
| 下午 | 架構師 agent 建立完整 API contract 對照表 |
| 下午 | 全端工程師 agent 修正 8 個 API 檔、5 個 components、4 個 views |
| 傍晚 | 後端 QA + 前端 QA 並行審查，發現 17 Critical + 9 Major 問題 |
| 傍晚 | 修復 agent 修正全部 Critical/Major，build 通過，部署完成 |

---

## 📊 2026-04-06 系統狀態（最新）

### user-app 前端完整 API 對接狀態

| 頁面 | API Endpoint | 狀態 |
|------|-------------|------|
| 登入 | POST /api/auth/login | ✅ |
| 儀表板 | GET /api/user/stats/today | ✅ |
| 訂單管理 | GET /api/user/orders | ✅ 151筆 |
| 出貨管理 | GET /api/user/shipments | ✅ 2筆 |
| 退貨管理 | GET /api/user/refunds | ✅ 1筆 |
| 商品管理 | GET /api/user/products | ✅ 3筆 |
| 賣場管理 | GET /api/user/sellpacks | ✅ 4筆 |
| 庫存管理 | GET /api/user/inventory | ✅ 3筆 |
| 銷售報表 | GET /api/user/reports/sales | ✅ |
| 通路設定 | GET /api/user/channels/platforms | ✅ 5筆 |
| 商家設定 | GET /api/user/settings | ✅ |

### 後端 API 全量測試結果（2026-04-06）
17/17 endpoint 全部 200，無失效。
唯一已知問題：`/reports/profit` 的 `totalCost`/`grossProfit`/`grossMargin` 全為 null（後端計算邏輯未完成）。

### 已修復的前端問題（本日）
- **Critical×8**：syncStatus 崩潰、types 欄位錯誤、SellPackForm 未存資料、ProductForm 雙重解析、PUT→PATCH、Channel URL/欄位、ProductPage 新增按鈕
- **Major×9**：ProductTable 欄位名、SellPackTable 欄位名、RefundTable 按鈕條件、reject reason 丟失、低庫存 endpoint、product keyword 無效、Dashboard 統計數字、User type 欄位、ChannelPage channel 顯示

### 已知待處理（Minor）
- OrderPage 訂單詳情頁（placeholder）
- SellPackPage 批量更新 / retry（placeholder）
- ShipmentPage 使用 orderAPI.ship() 繞過 shipment workflow
- /reports/profit 後端計算邏輯待補

---

## 🔧 關鍵技術備忘（前端工程師必讀）

### Token Key
全部統一：`authToken`（localStorage）
涉及：`api/index.ts` interceptor、`stores/auth.ts`、`router/index.ts`、`App.vue` 登出

### 後端分頁規則（不一致，技術債）
| Controller | page 起始 | size param |
|-----------|----------|-----------|
| Orders / Refunds / Shipments / Inventory | **1** | `pageSize` |
| Products / SellPacks | **0** | `size` |

### API Response 格式
- 分頁列表：`{ data: T[], pagination: { total, pages, page, pageSize } }`
- 單一物件：flat（無 wrapper）
- Channels / Platforms list：直接 array（無 wrapper）
- Stats：`{ data: {...}, success: true }`
- Reports：flat object（`{ summary, byDate, byPlatform }`）

---

## 🎯 下一步工作項

### 技術債（建議本週處理）
- [ ] 統一後端分頁為 1-indexed + `pageSize`（Products / SellPacks controller）
- [ ] 補完 `/reports/profit` 的 cost / revenue join 計算
- [ ] ShipmentPage 改用完整 shipment workflow（`UserShipmentController`）

### 功能完善
- [ ] 訂單詳情頁
- [ ] 通路設定頁（顯示已設定 channels + 新增/編輯 token）
- [ ] SellPackPage 批量更新

---

**最後更新**: 2026-04-06（前端全面修復 + 二次審查通過）
**維護者**: Tom + Claude
