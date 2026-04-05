# SimpleEC OMS — 規則書導覽

> 每份 flow 文件涵蓋一條完整業務流程，從頁面到 DB 的各層規則都在同一份文件內。
> 每個角色進來找自己對應的 section，不需要跨文件。

---

## 角色對照表

| 角色 | 在每份 flow/ 文件讀哪個 section | 必讀 tech/ 文件 |
|------|-------------------------------|----------------|
| **PM** | §1 業務規則 | — |
| **Architect** | 全部 section | 全部 tech/ |
| **Frontend** | §2 前端 / API | `tech/rest-api.md` |
| **後端（API 層）** | §2 前端 / API、§5 後端 Job | `tech/rest-api.md`、`tech/db-conventions.md` |
| **後端（Channel Job）** | §3 Kafka 契約、§4 Channel Job、§7 Platform API | `tech/kafka-envelope.md`、`tech/platform-api.md`、`tech/capabilities-model.md` |
| **後端（Handler / Job）** | §3 Kafka 契約、§5 後端 Job、§8 Cache | `tech/kafka-envelope.md`、`tech/cache.md`、`tech/capabilities-model.md` |
| **DBA** | §6 DB | `tech/db-conventions.md` |
| **QA** | §9 QA Checklist | 全部 tech/ |

---

## flows/ — 業務流程規則（主體）

每份文件涵蓋一條端對端流程，內含固定 9 個 section：

| 流程文件 | 涵蓋範圍 |
|---------|---------|
| [`flows/inventory-sync.md`](flows/inventory-sync.md) | 庫存異動 → 同步至各平台；多倉邏輯（Shopify）；sell_pack_inventory 快照更新 |
| [`flows/order-processing.md`](flows/order-processing.md) | Scheduler → Channel Job 抓單 → ORDER_UPSERT → 寫入 DB；去重；PII 加密 |
| [`flows/product-sync.md`](flows/product-sync.md) | SYNC_PACK：Channel Job 抓套包 → Backend Job 建立 Pack→Product 映射 |
| [`flows/shipment.md`](flows/shipment.md) | 商家建出貨 → SHIP_ORDER → Channel Job 呼叫平台出貨 API |
| [`flows/return-flow.md`](flows/return-flow.md) | 平台退貨通知 → RETURN_UPSERT → 商家核准/拒絕 → APPROVE/REJECT_RETURN |

---

## tech/ — 共用技術規範（flows 引用）

每份 flow 文件內的技術規則以此為準，不重複定義：

| 技術文件 | 涵蓋範圍 | 主要讀者 |
|---------|---------|---------|
| [`tech/kafka-envelope.md`](tech/kafka-envelope.md) | Header/Body 結構、所有 TaskType 的 body 契約、topic 路由表、isRollback、error routing | 後端、QA |
| [`tech/db-conventions.md`](tech/db-conventions.md) | PK（NanoID）、FK、TIMESTAMPTZ、PII 加密、JSONB 用法、Flyway 規範、命名、Index、NULL 語意 | DBA、後端 |
| [`tech/rest-api.md`](tech/rest-api.md) | /api prefix 規則、JWT 認證、統一 response 格式、HTTP 方法、錯誤碼、分層規則 | Frontend、後端 API 層 |
| [`tech/platform-api.md`](tech/platform-api.md) | 各平台認證方式、Token 管理、Rate Limit、分頁策略、Webhook 接收規則、跨平台踩坑清單 | 後端 Channel Job |
| [`tech/cache.md`](tech/cache.md) | Redis 去重 pattern、key 命名規範、雙層去重機制、Redis 失敗降級規則 | 後端 |
| [`tech/capabilities-model.md`](tech/capabilities-model.md) | platform.capabilities JSONB schema、已知 key 定義、**禁止 hardcode 平台名稱**、讀取方式、新增 capability 流程 | 後端（所有層）|

---

## 關鍵架構原則（快速提醒）

以下原則貫穿所有流程文件，違反即視為架構退化：

1. **Translation Layer Rule** — Channel Job 是唯一翻譯器。Kafka 訊息只帶 NanoID，不帶平台 ID。
2. **Scheduler 只傳時間戳** — Queue 不含時間範圍；Channel Job 自行決定所有時間窗口。
3. **capabilities 驅動行為** — 禁止 `if platformName.equals("shopify")`，讀 `platform.getCapabilities().path("key").asBoolean(false)`。
4. **Channel Job 禁止存取 DB** — 只透過 Kafka 傳遞資料，不直接查詢資料庫。
5. **OMS 是被動同步方** — 接受平台任何狀態跳轉，不做狀態轉換驗證。
6. **PII 必須加密** — buyer_name / buyer_phone / buyer_email / shipping_address 透過 EncryptedFieldTypeHandler 處理。
