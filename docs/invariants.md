# SimpleEC OMS — Invariants（機器可驗契約）

> **這是什麼**：把 SimpleEC 散在 `WORK_PRINCIPLES.md`(約法三章)、`DESIGN_RULES.md`、`CLAUDE.md` 核心設計原則、與 8 個 domain brain 裡的**散文規則**,蒸餾成**機器可驗的不變量**。LOOP(`docs/workflow.md`)的 QA 對這些 INV 跑紅藍對抗;merge gate 看這些 INV 是否守住。
>
> **狀態**：v0 **種子**(2026-06-12,pre-hoc 蒸餾)。**不完整**——INV 會隨 cycle accrete(就像 home123_new 從 R35 長出 50+ 條)。種子先抓「已知紅線」,尤其安全/事件流負空間。
>
> **驗證手段(SimpleEC 沒有 unit test,目前 0 測試檔)**：每條 INV 標一個**斷言方式**,優先用「事件/DB/API 斷言」,不靠 unit test。自動化 test 進 backlog 慢慢補。

## 驗證層代碼

| 代碼 | 怎麼驗 | 成本 |
|---|---|---|
| `S` | static:grep / git diff / 讀 DDL | 0(免費) |
| `DB` | 查 Postgres 狀態 | 低 |
| `EVT` | Kafka 斷言:produce 訊息 → consume → 斷言 topic/狀態 | 中 |
| `API` | curl endpoint 驗回應 | 低 |
| `SMOKE` | 真人 / UI 操作 | 高(primary detection) |

## 嚴重度

`P0` 安全/資料遺失/事件流斷裂 · `P1` 跨平台同步錯/狀態錯 · `P2` 規範/技術債

---

## INFRA — 基礎架構不動(約法三章 #1)

### INV-INFRA-001 `P0` `S`
**Statement**: 任何 PR MUST NOT 修改 `docker-compose.yml` 的容器/網路定義、PostgreSQL schema 連線配置、Kafka broker/KRaft 配置、Nginx 反向代理基本配置、systemd/cron 啟動機制 —— 除非 PR 明確標記 `infra-change` 且 Tom 授權。
**允許**:日誌級別、資源限制、retention、監控告警規則。
**驗**:`git diff` 掃這些路徑。**Attack**:偷改 docker-compose 服務定義 / 改 Kafka partition 策略未授權。

---

## SEC — 跨層安全一致(約法三章 #2)

### INV-SEC-001 `P0` `S`+`SMOKE`
**Statement**: JWT 認證 / CORS / session 管理 / SSL/TLS / 速率限制 任一改動 MUST 在 **Admin App(8084)、User App(5173)、Nginx(8089)、API Gateway(8080→8082)** 四層同步,不可只改一層。
**驗**:改動清單比對四層配置。**Attack**:只在 gateway 改 CORS 不動 nginx → 出現不一致放行。

### INV-SEC-002 `P0` `S`
**Statement**: 任何讀取加密欄位(`buyer_name`/`buyer_phone`/`buyer_email`/`shipping_address`)的程式碼 MUST 包在 `EncryptionContext.setMerchantId()` … `clear()` 之間。
**驗**:grep 加密欄位讀取點,檢查前後有 setMerchantId/clear。**Attack**:漏 setMerchantId 直接讀 → 解密失敗或跨商家解錯。

---

### INV-SEC-003 `P0` `EVT`
**Statement**: `order.process`(及 `return.process`)topic 訊息的 buyer 個資欄位(buyerName/Phone/Email/shippingAddress/buyerInfo)MUST 為密文 —— PII 在 producer(channel-job + Excel 匯入)送 Kafka 前就加密,topic 上不得出現明文 buyer 個資。
**Origin**: 2026-06-12 loop QA(excel WIP 驗證)挖到系統級 finding。詳見 `docs/cycles/pii-encrypt-at-source-migration.md`。
**驗**:撈一筆 order.process 訊息,buyerName 為 base64 密文(非中文)。**Attack**:某 producer 漏加密 → 明文 PII 上 Kafka。

### INV-SEC-004 `P0` `S`
**Statement**: 訂單處理(`OrderUpsertConsumer` / order-job 模組)MUST NOT 含任何 encrypt/decrypt 呼叫 —— 加解密只在 producer(寫前)與 entity 讀取層(passthrough converter 讀時),訂單處理只判 upsert。
**Origin**: 2026-06-12 Tom 硬性要求。
**驗**:`grep -rE "encrypt|decrypt|PiiEncryptor" simpleec-order-job` = 空。**Attack**:在 consumer 加解密步驟。

## EVENT — 事件流完整性(約法三章 #3)

### INV-EVENT-001 `P0` `S`
**Statement**: 改動任一 Kafka producer / consumer / handler MUST 同步檢查全鏈路:所有相關 topic、所有 producer+consumer、message schema 對齊、handler 邏輯、DLT/retention。
**驗**:PR 必附「事件全景清單」(涉及 topic / producer / consumer)。**Attack**:改 producer 送出的 body 欄位,不改對應 consumer → consumer 解析失敗靜默掉訊息。

### INV-EVENT-002 `P0` `EVT`
**Statement**: 任何訊息 MUST 不會「消失」—— 不可重試的訊息 MUST 進 `task.dlt`(保留 30d),可重試的 MUST 進 `task.failed`(保留 1d),poison pill 由 `DefaultErrorHandler`+`FixedBackOff(0,0)` 跳過。
**驗**:produce 一個會 throw 的訊息 → 斷言 `task.failed` 或 `task.dlt` 有它。**Attack**:handler 拋 RuntimeException → 看訊息是否靜默消失(沒進 failed/dlt)。

### INV-EVENT-003 `P1` `S`
**Statement**: `order.process` 與 `return.process` 是 Source of Truth;其他 topic 的 consumer MUST NOT 繞過它們直接改訂單/退貨主狀態。
**驗**:grep 各 handler,訂單主狀態寫入只在 order/return.process consumer。**Attack**:backend-job 直接 UPDATE orders.status。

---

## KAFKA — 訊息層

### INV-KAFKA-001 `P1` `EVT`
**Statement**: 所有 Kafka 訊息 MUST 採統一 Header/Body 結構;header MUST 含 `taskType`/`merchantId`/`platformId`/`requestId`/`timestamp`/`source`/`version`/`isRollback`。
**驗**:抽樣各 topic 訊息驗 header 欄位齊全。**Attack**:發一個缺 `requestId` 或缺 `version` 的訊息。

### INV-KAFKA-002 `P0` `EVT`
**Statement**: `SchemaVersionHandler.normalize()` 對不支援的 version MUST 路由到 `task.dlt`,不可拋例外讓 consumer 掛掉。
**驗**:發 `version=999` 的訊息 → 斷言進 `task.dlt` 且 consumer 仍存活。**Attack**:發未知版本看 consumer group 是否 crash/卡 rebalance。

### INV-KAFKA-003 `P0` `EVT`+`DB`
**Statement**: 同一 `requestId`(或同一 channelOrderId)的訊息重複消費 MUST NOT 造成重複副作用(訂單去重靠 Redis order hash)。
**驗**:重發同 requestId 兩次 → 斷言 DB 只一筆訂單。**Attack**:重送 `PROCESS_ORDER` → 出現兩筆訂單 / 庫存扣兩次。

---

## LAYER — 三層職責分離(Scheduler / Channel Job / Handler)

### INV-LAYER-001 `P1` `S`
**Statement**: Channel Job 模組 MUST NOT 存取資料庫、做業務邏輯驗證(庫存/積分)、管理訂單狀態。它只負責 API 呼叫、時間窗口計算、格式轉換、分頁、發 Kafka。
**驗**:grep `simpleec-channel-job` 無 Mapper/Repository/DB import、無訂單狀態寫入。**Attack**:在 channel job 注入 OrderMapper 直接寫 DB。

### INV-LAYER-002 `P1` `EVT`
**Statement**: Scheduler / Queue 訊息 body MUST 只含時間戳(timestamp),MUST NOT 含 `from`/`to`/任何 range —— 時間窗口由 Channel Job 自主計算。
**驗**:抽樣 `scheduler` topic 訊息,body 無 range 欄位。**Attack**:scheduler 塞 `{from, to}` 進 queue。

---

## XLATE — Translation Layer Rule(強制 / 違反=架構退化)

### INV-XLATE-001 `P1` `EVT`
**Statement**: 所有 Kafka 訊息 MUST 用 OMS 內部 NanoID,MUST NOT 帶平台 ID(`channelOrderId`/`channelProductId`/`channelSpecId`)—— **例外**:inbound `ORDER_UPSERT`/`RETURN_UPSERT` 可帶 `channelOrderId`(內部 ID 尚未建立)。
**驗**:抽樣 outbound action 訊息,ID 形態為 20 字元 NanoID 而非平台格式。**Attack**:`UPDATE_INVENTORY` 訊息帶 `channelProductId`。

### INV-XLATE-002 `P1` `S`
**Statement**: outbound action 的 channel ID MUST 由 handler 查表取得,不由訊息攜帶:`UPDATE_INVENTORY`/`UPDATE_PRICE`→body 帶 `sellPackId`(查 sell_pack);`SHIP_ORDER`→`orderId`(查 orders);`APPROVE_RETURN`/`REJECT_RETURN`→`returnId`(查 refund_orders)。
**驗**:grep 各 outbound handler 有對應查表。**Attack**:handler 直接信任訊息裡的 channel_order_id 不查表。

---

## DATA — 資料完整性

### INV-DATA-001 `P2` `S`
**Statement**: 所有資料表 PK MUST 是 `VARCHAR(20)` NanoID(程式端 `IdGenerator.nextId()`),所有 FK 亦為 `String`。
**驗**:掃 `db/migration/*.sql` DDL。**Attack**:新 migration 用 `SERIAL`/`BIGINT`/`UUID` 當 PK。

### INV-DATA-002 `P0` `S`+`DB`
**Statement**: PII 欄位(`buyer_name`/`buyer_phone`/`buyer_email`/`shipping_address`)在 DB MUST 為密文(AES-256-GCM),不可明文落地。
**驗**:`SELECT buyer_phone FROM orders LIMIT 1` 應為密文。**Attack**:某路徑繞過 `EncryptedFieldTypeHandler` 寫明文。

---

## ORDER — 訂單語意

### INV-ORDER-001 `P1` `EVT`+`DB`
**Statement**: `isRollback=true` 的回補訂單,業績 MUST 追溯 `channelCreatedAt` 原日期計入,不計當日;統計標記為回補。
**驗**:發 `isRollback=true` 訂單(channelCreatedAt=上月)→ 斷言統計歸上月。**Attack**:回補訂單業績計到當日 → 日報錯。

### INV-ORDER-002 `P1` `EVT`
**Statement**: 系統是**被動同步方**,訂單狀態 MUST 接受任何狀態跳轉,不做狀態轉換合法性驗證(所有邏輯容錯)。
**驗**:發 `COMPLETED→PENDING` 跳轉 → 斷言不報錯、正常更新。**Attack**:某 handler 加了「非法狀態跳轉」拒絕邏輯 → 平台資料被擋。

---

## DESIGN — 設計必要性(約法三章 #4)→ PM review-gate(非 runtime INV)

### INV-DESIGN-001 `P2` `(PM-gate)`
**Statement**: 新增 cache / 預計算 / Kafka 事件驅動之前,PR MUST 回答頻次×商務三問(多久觸發?計算多貴?即時或最終一致?)並 justify。
**性質**:這條**機器驗不了**(是設計判斷),屬 **L0 spec gate**——放 PM 第一道閘人工把關,不是 runtime 斷言。列在此提醒「過度設計也是違規」。

---

## Backlog（要 accrete 的方向）

- [ ] 把高頻 INV 的「事件/DB/API 斷言」腳本化(目前是手動步驟)→ 起 test seed
- [ ] 補各平台(shopee/momo/yahoo/easystore/cyberbiz/pchome)的 SYNC 正確性 INV(對應 5 個平台 brain)
- [ ] 補 retry-job / DLT 路由的 INV
- [ ] 退貨流程(return.process)的狀態 INV
- [ ] 每修一個 bug → 加一條對應 INV(post-hoc,跟 pre-hoc 種子合流)
