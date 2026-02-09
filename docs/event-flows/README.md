# SimpleEC OMS — 事件流設計文件

> **依據: SCHEMA.md v4（2026-02-09）**
>
> **核心原則：DB schema ↔ Kafka payload ↔ JOB 邏輯 三者必須完全一致，不可有任何欄位遺漏。**
>
> 每個事件流文件都包含：
> 1. **觸發方式** — 誰發的、怎麼觸發
> 2. **完整 JSON payload** — 每個欄位對應到哪張 DB 表的哪個欄位
> 3. **JOB 處理邏輯** — 逐步操作，讀哪張表、寫哪張表、發到哪個 topic
> 4. **DB schema 欄位對照表** — payload ↔ DB 欄位的完整 mapping
> 5. **Entity 缺口分析** — 目前 Java Entity 缺少哪些欄位需要補
> 6. **失敗處理** — 失敗時怎麼走

## 關鍵設計規則（SCHEMA v4）

- **PK**: 所有表 VARCHAR(20) NanoID（程式端產生）
- **SKU 命名**: 全系統統一為 `sku`（product.sku, sell_pack.sku, orders.items[].sku）
- **訂單明細**: `orders.items` JSONB（無 order_items 獨立表）
- **商品 = SKU 級別**: 無 product_spec 表，不同規格 = 不同 product
- **product_group**: 前端管理用，把同商品不同規格綁成一組

## 文件清單

| 文件 | 事件流 | 涉及 JOB | 涉及 DB 表 |
|------|--------|---------|-----------|
| [FETCH_PRODUCTS.md](FETCH_PRODUCTS.md) | 同步商品 | ChannelJob | product, product_barcode, sell_pack, channel |
| [FETCH_ORDERS.md](FETCH_ORDERS.md) | 拉單 | ChannelJob → OrderProcessJob → BackendJob | orders (items JSONB), sell_pack, order_status_logs |
| [DB_ENTITY_GAPS.md](DB_ENTITY_GAPS.md) | — | — | 所有表的 DB ↔ Entity 差異彙整 |

## 欄位一致性檢查清單

設計/開發新事件流時，依此清單逐項確認：

- [ ] DB schema 欄位 → Java Entity 有對應 field
- [ ] Java Entity field → DB schema 有對應 column
- [ ] Kafka payload 每個 key → DB 裡有欄位可以存
- [ ] JOB 邏輯裡讀的欄位 → payload 裡有帶、或 DB 裡有
- [ ] JOB 邏輯裡寫的欄位 → payload 裡有帶所需資料
- [ ] ChannelAdapter 回傳的資料 → 有欄位可以承接
- [ ] 平台端的 ID（channel_product_id, channel_spec_id）→ 有在 DB + Entity + payload 三處都存在
- [ ] 所有 ID 欄位（PK, FK）→ VARCHAR(20) NanoID（不是 BIGSERIAL）
- [ ] SKU 欄位名 → 統一叫 `sku`（不是 item_number, sku_code 等）
- [ ] JSONB 欄位（orders.items, refund_orders.items）→ 帶齊 sku, sellPackId, productId
