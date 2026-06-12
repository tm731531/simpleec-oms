# Migration Spec — PII 加密前移到 Source(encrypt-at-source)

> **Cycle Type**: `T-migration`(停機切換,動 production + 既有資料)
> **狀態**: spec v1(2026-06-12)— 待開工。code 一行未動。
> **觸發**: loop QA 對 WIP excel 功能驗證 → 挖到系統級 finding。
> **決策來源**: decision-server `7e2a-01`(excel raw_rows→D)+ 對話定案(Tom)。

---

## 1. Why(finding)

現有 PII 加密發生在 **DB 寫入層**(`EncryptedAttributeConverter`),導致 **buyer 個資在 Kafka `order.process` topic 上是明文**,違反設計意圖(個資應在送 Kafka 前就加密)。channel-job ×6 + 即將實作的 Excel 匯入皆中。

## 2. 現狀(已查證,file:line)

| 事實 | 證據 |
|---|---|
| channel-job 送明文 PII 進 Kafka | `ModeBOrderDetailHandler.java:176-205` `omsData.put("buyerName", nameObj.toString())`;整模組 grep `encrypt`=0 |
| 加密在 DB 寫入層 | `EncryptedAttributeConverter.convertToDatabaseColumn` → `encryptor.encrypt(...)` |
| converter 寫入端**無腦加密**(給密文會雙重加密) | 同上,無 already-encrypted 偵測 |
| converter 讀取端容錯(像明文就原樣回) | `convertToEntityAttribute` 註解 |
| **converter 被 Channel + Order 共用** | `Channel.java`(5 欄 = API token,admin 明文輸入)+ `Order.java`(4 欄 = buyer PII) |
| `buyerInfo` **完全沒加密** | `Order.java:149` `@JdbcTypeCode(JSON)` 無 `@Convert` → 明文 JSON 落地 |
| Excel 匯入 service 未實作 | grep 不到,只有 V9 表 |

## 3. 目標狀態

- buyer PII(`buyer_name`/`buyer_phone`/`buyer_email`/`shipping_address`/`buyerInfo`)在 **producer(channel-job + Excel 匯入)送 Kafka 前就加密**。
- `order.process` topic 上 **不得出現明文 buyer 個資**。
- **訂單處理(OrderUpsertConsumer)全程不碰加解密**(Tom 硬性要求)。
- Channel API token 加密**維持不變**。

## 4. 設計決策(c)— 專屬 passthrough converter

訂單處理不碰 crypto + converter 共用 → 不能全域 flip。採:

- **新增 `PreEncryptedPassthroughConverter`**:寫入=**原樣存**(source 已加密);讀取=**解密**(沿用 `PiiEncryptor.decrypt`,容錯明文)。
- **Order 的 5 個 PII 欄位**(含 `buyerInfo` 新增 `@Convert`)改用此 passthrough converter。
- **Channel 維持 `EncryptedAttributeConverter`**(token 仍 admin 明文輸入、DB 寫入時加密)。
- **OrderUpsertConsumer 不動 crypto**:照舊 set 欄位,passthrough converter 負責「原樣存 / 讀時解密」。

## 5. Code 改動範圍

| # | 模組 | 改動 | 不動 |
|---|---|---|---|
| 1 | `simpleec-core/crypto` | 新增 `PreEncryptedPassthroughConverter` | `EncryptedAttributeConverter`、`PiiEncryptor` 不動 |
| 2 | `simpleec-core/entity/Order` | 4 scalar PII 欄位改掛 passthrough converter(✅ 已做);`buyerInfo`/`shippingInfo` **維持 JSONB 不掛 converter**(加密的是 JSON 內 PII 欄位的「值」,由 producer 做、讀取端解密「值」) | 其他欄位不動 |
| 3 | `simpleec-channel-job` 各 order handler(ModeA/ModeB…6 平台) | 組 message 前 `PiiEncryptor.encrypt(value, merchantId)` 加密 5 欄 | 其餘邏輯不動 |
| 4 | Excel 匯入後台(新實作) | 同上,匯入時即加密 PII 才送 order.process | — |
| 5 | `OrderUpsertConsumer` | **不動 crypto** | upsert 判斷不動 |
| 6 | `Channel` entity / admin API | **完全不動** | — |

## 6. 既有資料處理

| 欄位 | 既有 row 狀態 | 需 backfill? |
|---|---|---|
| `buyer_name`/`phone`/`email`/`shipping_address` | 已被舊 converter 加密 | ❌ **不用**(passthrough 讀取端同樣解密、同金鑰) |
| `buyerInfo`/`shippingInfo` 內 PII 值 | **明文(JSON 內的值)** | ✅ **要 backfill**:walk JSON → 加密 name/phone/email/address 的「值」→ 寫回(整體仍合法 JSON) |

## 7. 切換 Runbook(drain-and-cutover,Tom 定序)

> 核心:明文/密文**不可同時在線**。靠停機 + drain 確保 pipeline 清空再換。

**下線(producer 先 → 訂單處理最後):**
1. [ ] 下線所有 producer:6 個 channel-job + Excel 匯入(停止新訊息進 order.process)
2. [ ] **drain**:`kafka-consumer-groups --describe` 確認 order.process consumer group **lag = 0**(殘留明文訊息全消化)
3. [ ] 下線 OrderUpsertConsumer(訂單處理)

**部署(pipeline 全空時):**
4. [ ] 部署新 core(passthrough converter + Order 註解)+ producer 加密 + Excel
5. [ ] 跑 `buyerInfo` backfill 腳本(既有明文 → 密文)

**上線(producer 先 → 訂單處理最後):**
6. [ ] 上線 channel-job ×6 + Excel(開始送密文,進 order.process 排隊)
7. [ ] **最後**上線 OrderUpsertConsumer(消化密文,passthrough 原樣存)

## 8. Rollback(逐步退)

- 任一階段失敗 → 反向:下線 producer → drain → 回滾 core/producer 到前一版(converter 退回 `EncryptedAttributeConverter`)→ 上線。
- `buyerInfo` backfill 前先 `pg_dump` 該欄位,可還原。
- 既有 buyer 欄位不動(無 backfill)→ 回滾零風險。

## 9. INV(驗收標準 → 進 invariants.md)

- **INV-SEC-003** `P0` `EVT`:`order.process` topic 訊息的 buyer 欄位 MUST 為密文。驗:撈一筆驗 buyerName 非明文。
- **INV-DATA-002**(已存在,擴充):`buyerInfo` 在 DB MUST 為密文(納入 @Convert)。
- **INV-SEC-004** `P0` `S`:OrderUpsertConsumer MUST NOT 含任何 encrypt/decrypt 呼叫(訂單處理不碰 crypto)。驗:grep consumer 模組無 PiiEncryptor/encrypt。

## 10. 驗證(event/DB 斷言,無 unit test)

- 切換後撈 1 筆 order.process → buyerName 密文(base64,非中文)= INV-SEC-003 ✓
- `SELECT buyer_info FROM orders ORDER BY created_at DESC LIMIT 1` → 密文 = INV-DATA-002 ✓
- 前台開一筆訂單 → buyer 姓名正常顯示(讀取端解密正常)= 端到端 ✓
- `grep -rE "encrypt|decrypt" simpleec-order-job` → 空 = INV-SEC-004 ✓
