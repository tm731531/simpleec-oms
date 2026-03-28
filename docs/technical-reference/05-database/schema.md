# 資料庫 Schema 參考

SimpleEC OMS 使用 PostgreSQL 16。所有主鍵均為 `VARCHAR(20)` NanoID 值，由應用程式程式碼透過 `IdGenerator.nextId()` 產生。系統中不存在 `SERIAL` 或 `BIGSERIAL` 欄位。

**權威來源**：`docker/init-db/01-schema.sql`（schema 版本 v4，2026-02-09）

---

## 資料表總覽

| # | 資料表 | 用途 |
|---|-------|---------|
| 1 | `platform_account` | SimpleEC SaaS 管理員帳號（與商家帳號獨立） |
| 2 | `global_config` | 全系統 key-value 設定 |
| 3 | `merchant` | 租戶／公司記錄 |
| 4 | `account` | 商家操作員帳號 |
| 5 | `merchant_options` | 商家自訂選項值 |
| 6 | `platform` | 電商平台定義（Shopee、Momo 等） |
| 7 | `channel_api_versions` | 平台 API 版本歷史 |
| 8 | `channel` | 商家在某平台上的店鋪實例 |
| 8b | `channel_shipping_mapping` | 每個通路的物流方式對應 |
| 9 | `product_group` | 商品群組／品牌（UI 管理用） |
| 10 | `product` | 內部 SKU 級商品 |
| 11 | `product_barcode` | 每個商品的多個條碼 |
| 12 | `sell_pack` | 刊登對應：商品 × 通路 |
| 13 | `orders` | 來自所有平台的正規化訂單 |
| 14 | `order_status_logs` | 訂單狀態變更稽核軌跡 |
| 15 | `order_shipments` | 出貨追蹤記錄 |
| 16 | `refund_orders` | 退貨／退款記錄 |
| 17 | `channel_sync_logs` | 通路 API 同步健康日誌 |
| 18 | `daily_statistics` | 分區的每日業務統計 |
| 19 | `failed_task_logs` | Kafka 死信持久化存儲 |

---

## 詳細資料表定義

### `platform_account`
用途：SimpleEC SaaS 平台管理員帳號。與商家租戶獨立——僅供平台層級管理使用。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `name` | VARCHAR(50) | NOT NULL | 管理員顯示名稱 |
| `email` | VARCHAR(256) | NOT NULL, UNIQUE | 登入 email |
| `password` | VARCHAR(512) | NOT NULL | Bcrypt 雜湊密碼 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'enable' | 帳號狀態：`enable`、`disable` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `platform_account_email` UNIQUE on `(email)`

---

### `global_config`
用途：全系統 key-value 設定存儲（功能開關、系統參數等）。key 即為 `id` 欄位（可讀字串，最長 128 字元）。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(128) | PK, NOT NULL | 設定鍵（可讀字串） |
| `data` | VARCHAR(2048) | NOT NULL | 設定值（JSON 或純字串） |
| `description` | VARCHAR(256) | | 設定的人類可讀說明 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

---

### `merchant`
用途：租戶記錄。系統中每一筆資料都以 `merchant_id` 為範圍。一個公司 = 一筆 merchant 記錄。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `merchant_name` | VARCHAR(256) | NOT NULL | 公司／店鋪名稱 |
| `merchant_email` | VARCHAR(256) | NOT NULL | 主要聯絡 email |
| `merchant_phone_number` | VARCHAR(20) | NOT NULL | 主要聯絡電話 |
| `tax_id_number` | VARCHAR(20) | NOT NULL | 公司稅籍／統一編號 |
| `address_city` | VARCHAR(50) | NOT NULL | 城市 |
| `address_region` | VARCHAR(50) | NOT NULL | 地區／州／縣 |
| `address_country` | VARCHAR(50) | NOT NULL | 國家 |
| `address_zip` | VARCHAR(10) | NOT NULL | 郵遞區號 |
| `address_phone_number` | VARCHAR(20) | NOT NULL | 地址電話（可能與商家電話不同） |
| `address_line1` | VARCHAR(256) | NOT NULL | 街道地址第一行 |
| `address_line2` | VARCHAR(256) | NOT NULL | 街道地址第二行 |
| `vip_level` | INTEGER | NOT NULL, DEFAULT 0 | 商家 VIP 等級（0 = 標準） |
| `user_local_time_zone` | VARCHAR(50) | NOT NULL, DEFAULT 'UTC' | 商家當地時區（用於顯示） |
| `payer_name` | VARCHAR(256) | NOT NULL | 帳務聯絡人姓名 |
| `payer_email` | VARCHAR(256) | NOT NULL | 帳務聯絡 email |
| `payer_phone_number` | VARCHAR(20) | NOT NULL | 帳務聯絡電話 |
| `status` | VARCHAR(20) | NOT NULL | 商家狀態：`active`、`suspended`、`trial` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

---

### `account`
用途：屬於某商家的操作員帳號。多個操作員可管理同一商家。支援 TOTP（2FA）。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `account_name` | VARCHAR(256) | NOT NULL | 操作員顯示名稱 |
| `account_email` | VARCHAR(256) | NOT NULL, UNIQUE | 登入 email（全域唯一） |
| `account_password` | VARCHAR(512) | NOT NULL | Bcrypt 雜湊密碼 |
| `account_tel` | VARCHAR(20) | | 選填電話號碼 |
| `is_main_account` | BOOLEAN | NOT NULL, DEFAULT false | true = 商家主帳號 |
| `access_level` | INTEGER | NOT NULL, DEFAULT 0 | 權限等級（0=唯讀，越高權限越大） |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'enable' | `enable`、`disable` |
| `totp_secret` | VARCHAR(20) | | TOTP 2FA 金鑰（null = 未啟用 2FA） |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `account_email_un` UNIQUE on `(account_email)`
- `fk_account_merchant` FK on `merchant_id` → `merchant(id)` ON UPDATE CASCADE

---

### `merchant_options`
用途：以商家為範圍的自訂下拉選項（如自訂訂單標籤、內部狀態值）。用於動態 UI 選項，無需硬編碼 enum。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `name` | VARCHAR(64) | NOT NULL | 選項顯示名稱 |
| `type` | VARCHAR(20) | NOT NULL | 選項分類（如 `order_tag`、`status_label`） |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'enable' | `enable`、`disable` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

---

### `platform`
用途：支援的電商平台定義。每個平台一筆記錄（Shopee、Momo 等）。此處儲存的憑證為平台層級預設值；通路層級憑證存於 `channel`。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `platform_name` | VARCHAR(50) | NOT NULL | 平台顯示名稱（如「Shopee Taiwan」） |
| `credential1` | VARCHAR(4096) | | 平台 API Key / App ID（加密） |
| `credential2` | VARCHAR(4096) | | 平台 API Secret / Partner Key（加密） |
| `actived` | BOOLEAN | NOT NULL, DEFAULT true | 平台是否啟用 |
| `queue_topic` | VARCHAR(128) | | 此平台的 Kafka topic 前綴 |
| `currency` | VARCHAR(3) | DEFAULT 'TWD' | 預設貨幣代碼（ISO 4217） |
| `ship_options` | JSON | | 支援的物流方式（JSON 陣列） |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

---

### `channel_api_versions`
用途：追蹤每個平台目前啟用的 API 版本。允許在不重新部署程式碼的情況下進行受控的 API 版本升級。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `platform_id` | VARCHAR(20) | NOT NULL, FK → platform.id | 所屬平台 |
| `api_version` | VARCHAR(20) | NOT NULL | API 版本字串（如 "v2.0"、"2024-01"） |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | 此版本是否目前使用中 |
| `effective_date` | DATE | | 此版本生效日期 |
| `description` | TEXT | | 此版本的備註說明 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |

---

### `channel`
用途：商家在特定平台上的店鋪實例。例如「商家 A 的 Shopee 店鋪」。`channel_id` 作為所有訂單和同步資料的主要分區鍵。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `platform_id` | VARCHAR(20) | NOT NULL, FK → platform.id | 所屬平台 |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `channel_sn` | VARCHAR(128) | | 平台指定的店鋪識別碼 |
| `channel_name` | VARCHAR(256) | | 店鋪顯示名稱 |
| `multi_spec` | BOOLEAN | NOT NULL, DEFAULT false | 店鋪是否販售多規格商品 |
| `token` | VARCHAR(4096) | NOT NULL | 主要 API 憑證 / 存取 Token（加密） |
| `token2` | VARCHAR(4096) | | 次要 Token（如 Refresh Token） |
| `token3` | VARCHAR(4096) | | 第三 Token / 額外憑證 |
| `token4` | VARCHAR(4096) | | 額外憑證欄位 |
| `token5` | VARCHAR(4096) | | 額外憑證欄位 |
| `actived` | BOOLEAN | NOT NULL, DEFAULT true | 通路是否啟用 |
| `write_actived` | BOOLEAN | NOT NULL, DEFAULT false | 是否啟用回寫平台功能 |
| `enable_sync` | BOOLEAN | NOT NULL, DEFAULT false | 是否啟用定期同步 |
| `first_sync_start_time` | TIMESTAMPTZ | | 初始歷史同步開始時間 |
| `first_sync_end_time` | TIMESTAMPTZ | | 初始歷史同步完成時間 |
| `last_sync_time` | TIMESTAMPTZ | | 最近一次成功同步的時間戳 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `idx_channel_platform` on `(platform_id)`
- `idx_channel_merchant` on `(merchant_id)`

---

### `channel_shipping_mapping`
用途：將平台原始的物流方式代碼（如 "SHOPEE_711"）對應到內部使用的物流公司名稱。允許跨平台正規化物流資料。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `channel_id` | VARCHAR(20) | NOT NULL, FK → channel.id | 所屬通路 |
| `platform_shipping_method` | VARCHAR(100) | NOT NULL | 平台 API 原始物流方式代碼 |
| `logistics_company` | VARCHAR(100) | NOT NULL | 可讀的物流公司名稱 |
| `logistics_company_code` | VARCHAR(50) | | 內部物流公司代碼 |
| `description` | VARCHAR(256) | | 備註或說明 |
| `active` | BOOLEAN | NOT NULL, DEFAULT true | 此對應是否啟用 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `idx_shipping_map_channel` on `(channel_id)`
- `idx_shipping_map_merchant` on `(merchant_id)`
- `uk_shipping_map` UNIQUE on `(channel_id, platform_shipping_method)`

---

### `product_group`
用途：將相關商品依品牌或分類分組，供 UI 管理使用。選填——商品可以不屬於任何群組。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `group_name` | VARCHAR(512) | NOT NULL | 群組顯示名稱 |
| `description` | TEXT | | 群組說明 |
| `brand` | VARCHAR(100) | | 品牌名稱 |
| `main_image_url` | VARCHAR(1024) | | 代表性商品圖片 URL |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'active' | `active`、`inactive` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `idx_product_group_merchant` on `(merchant_id)`

---

### `product`
用途：內部 SKU 級商品。這是 OMS 的規範性商品記錄，可連結多筆 sell_pack 記錄（每個通路每個 SKU 對應一個刊登）。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `product_group_id` | VARCHAR(20) | FK → product_group.id, ON DELETE SET NULL | 選填商品群組 |
| `sku` | VARCHAR(100) | NOT NULL | SKU 代碼（同一商家內唯一） |
| `name` | VARCHAR(512) | NOT NULL | 商品名稱 |
| `spec_summary` | VARCHAR(256) | | 規格簡述（如「紅色 / M」） |
| `cost_price` | DECIMAL(12,2) | | 成本價（用於毛利計算） |
| `suggest_price` | DECIMAL(12,2) | | 建議售價 |
| `quantity` | INTEGER | NOT NULL, DEFAULT 0 | 目前庫存數量 |
| `safety_quantity` | INTEGER | NOT NULL, DEFAULT 0 | 補貨點／安全庫存量 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'active' | `active`、`inactive`、`discontinued` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `idx_product_merchant_sku` UNIQUE on `(merchant_id, sku)`
- `idx_product_merchant_status` on `(merchant_id, status)`
- `idx_product_group` on `(product_group_id)`

---

### `product_barcode`
用途：儲存每個商品的多個條碼（EAN、UPC、ISBN、QR 等）。一個商品可有多個條碼；其中一個指定為主要條碼。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `product_id` | VARCHAR(20) | NOT NULL, FK → product.id CASCADE | 所屬商品 |
| `barcode` | VARCHAR(50) | NOT NULL | 條碼值 |
| `label` | VARCHAR(100) | | 條碼類型標籤（如 "EAN-13"、"QR"） |
| `is_primary` | BOOLEAN | NOT NULL, DEFAULT false | 是否為主要條碼 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |

索引：
- `idx_barcode_product` on `(product_id)`
- `idx_barcode_value` on `(barcode)` — 用於依條碼掃描反向查詢

---

### `sell_pack`
用途：通路上的刊登——內部商品與通路店鋪之間的對應關係。一個商品可有多筆 sell_pack 記錄（每個通路一筆，或同一通路的不同規格組合各一筆）。儲存通路特定資料（平台商品 ID、規格 ID、價格）。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `merchant_id` | VARCHAR(20) | NOT NULL | 所屬商家（反正規化以提升查詢效能） |
| `product_id` | VARCHAR(20) | NOT NULL, FK → product.id | 內部商品 |
| `channel_id` | VARCHAR(20) | NOT NULL, FK → channel.id | 目標通路 |
| `sku` | VARCHAR(100) | NOT NULL | SKU（從商品反正規化以快速查詢） |
| `channel_product_id` | VARCHAR(256) | | 平台的商品 / 刊登 ID |
| `channel_spec_id` | VARCHAR(256) | | 平台的規格 / 變體 ID |
| `channel_product_name` | VARCHAR(512) | | 商品在平台上的刊登名稱 |
| `channel_spec_name` | VARCHAR(256) | | 規格 / 變體在平台上的名稱 |
| `channel_product_url` | VARCHAR(1024) | | 平台刊登頁面 URL |
| `title` | VARCHAR(512) | | OMS 顯示標題（可能與通路名稱不同） |
| `channel_spec_attrs` | JSONB | | 平台特定規格屬性（如 `{"color":"紅色","size":"M"}`） |
| `selling_price` | DECIMAL(12,2) | | 通路上目前的售價 |
| `quantity` | INTEGER | NOT NULL, DEFAULT 0 | 同步到此通路刊登的目前數量 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'draft' | `draft`、`active`、`inactive`、`deleted` |
| `visibility` | VARCHAR(20) | | 平台可見性：`VISIBLE`、`HIDDEN` |
| `last_sync_at` | TIMESTAMPTZ | | 最近一次與平台同步的時間 |
| `sync_status` | JSONB | | 同步追蹤狀態：`{"state":"pending|syncing|completed|failed","message":"..."}` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `idx_sellpack_merchant` on `(merchant_id)`
- `idx_sellpack_channel` on `(channel_id)`
- `idx_sellpack_product` on `(product_id)`
- `idx_sellpack_sku` on `(sku)`
- `idx_sellpack_upsert_key` UNIQUE on `(channel_id, channel_product_id, COALESCE(channel_spec_id, ''))`
  — 允許同一商品以不同規格在同一通路多次刊登，同時防止相同通路 + 商品 + 規格組合的重複刊登。

---

### `orders`
用途：規範性訂單資料表。所有平台的訂單均正規化後儲存於此。PII 欄位（買家姓名、電話、email、地址）以 AES-256-GCM 加密後儲存。`items` 以 JSONB 格式儲存，避免額外建立 order_items 資料表。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵（OMS 訂單 ID） |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `channel_id` | VARCHAR(20) | NOT NULL, FK → channel.id | 來源通路 |
| `channel_order_id` | VARCHAR(100) | NOT NULL | 平台的訂單 ID（原始值，如 Shopee 的 `order_sn`） |
| `channel_order_number` | VARCHAR(100) | | 平台的可讀訂單編號（如 Cyberbiz 的 `#4319`） |
| `order_status` | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | OMS 統一狀態——見 `OrderStatusEnum` |
| `buyer_name` | VARCHAR(512) | | **PII** — AES-256-GCM 加密儲存 |
| `buyer_phone` | VARCHAR(256) | | **PII** — AES-256-GCM 加密儲存 |
| `buyer_email` | VARCHAR(512) | | **PII** — AES-256-GCM 加密儲存 |
| `buyer_info` | JSONB | | 買家額外元資料（未加密——無直接 PII） |
| `shipping_address` | TEXT | | **PII** — AES-256-GCM 加密儲存 |
| `shipping_method` | VARCHAR(50) | | 物流方式代碼 |
| `shipping_info` | JSONB | | 物流元資料（追蹤、預計送達等） |
| `payment_method` | VARCHAR(50) | | 付款方式（如 `credit_card`、`cod`、`transfer`） |
| `total_amount` | DECIMAL(12,2) | NOT NULL, DEFAULT 0 | 合計金額（商品 + 運費 - 折扣） |
| `shipping_fee` | DECIMAL(12,2) | NOT NULL, DEFAULT 0 | 運費 |
| `discount_amount` | DECIMAL(12,2) | NOT NULL, DEFAULT 0 | 總折扣金額 |
| `items` | JSONB | NOT NULL, DEFAULT '[]' | 訂單明細陣列（見下方結構） |
| `is_rollback` | BOOLEAN | NOT NULL, DEFAULT false | `true` = 歷史回補訂單 |
| `refund_amount` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | 累計退款金額（每次退款後更新） |
| `has_refund` | BOOLEAN | NOT NULL, DEFAULT false | 快速過濾旗標——若有任何退款則為 `true` |
| `channel_created_at` | TIMESTAMPTZ | | 訂單在平台上的建立時間（用於統計歸屬） |
| `paid_at` | TIMESTAMPTZ | | 付款確認時間 |
| `shipped_at` | TIMESTAMPTZ | | 出貨時間 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | OMS 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

**`items` JSONB 結構**（訂單明細陣列）：
```json
[
  {
    "channelItemId":  "platform-specific-item-id",
    "productName":    "商品名稱",
    "sku":            "SKU-001",
    "quantity":       2,
    "unitPrice":      790.00,
    "totalPrice":     1580.00
  }
]
```

**`OrderStatusEnum` 值**（跨所有平台統一）：
`pending` → `confirmed` → `ready_to_ship` → `shipping` → `shipped` → `completed` | `cancelled`

索引：
- `idx_order_channel_order` UNIQUE on `(channel_id, channel_order_id)` — upsert 鍵
- `idx_order_merchant_status` on `(merchant_id, order_status)`
- `idx_order_created` on `(created_at DESC)` — 用於最近訂單查詢
- `idx_order_items` GIN on `(items)` — 用於 JSONB 包含查詢
- `idx_order_stats` on `(merchant_id, channel_id, channel_created_at)` — 用於統計查詢
- `idx_order_has_refund` PARTIAL on `(merchant_id, has_refund) WHERE has_refund = true`

---

### `order_status_logs`
用途：訂單狀態轉換的不可變稽核軌跡。每次狀態變更記錄一筆。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `order_id` | VARCHAR(20) | NOT NULL, FK → orders.id CASCADE | 所屬訂單 |
| `from_status` | VARCHAR(20) | | 變更前狀態（初始狀態為 null） |
| `to_status` | VARCHAR(20) | NOT NULL | 變更後狀態 |
| `operator` | VARCHAR(100) | | 操作者（`system`、帳號 email 或商家 ID） |
| `remark` | TEXT | | 狀態變更的選填備註 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 轉換發生時間 |

索引：
- `idx_order_status_log_order` on `(order_id)`

---

### `order_shipments`
用途：訂單的出貨追蹤記錄。一筆訂單可有多筆出貨記錄（分批出貨）。包含追蹤號碼和物流公司資訊。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `order_id` | VARCHAR(20) | NOT NULL, FK → orders.id CASCADE | 所屬訂單 |
| `tracking_number` | VARCHAR(100) | | 物流公司追蹤號碼 |
| `logistics_company` | VARCHAR(100) | | 物流公司名稱 |
| `shipping_status` | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | `PENDING`、`IN_TRANSIT`、`DELIVERED`、`FAILED` |
| `shipped_at` | TIMESTAMPTZ | | 貨物交給物流的時間 |
| `delivered_at` | TIMESTAMPTZ | | 貨物送達時間 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 記錄建立時間 |

索引：
- `idx_shipment_order` on `(order_id)`
- `idx_shipment_tracking` on `(tracking_number)` — 用於追蹤號碼查詢

---

### `refund_orders`
用途：連結至上層訂單的退貨／退款記錄。一筆訂單可有多筆退款（部分退款、多次退貨申請）。`items` JSONB 鏡像 orders.items 結構，用於記錄退貨商品。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `order_id` | VARCHAR(20) | NOT NULL, FK → orders.id | 所屬訂單 |
| `merchant_id` | VARCHAR(20) | NOT NULL | 所屬商家（反正規化） |
| `channel_refund_id` | VARCHAR(100) | | 平台的退款 / 退貨申請 ID |
| `refund_status` | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | `PENDING`、`APPROVED`、`REJECTED`、`COMPLETED` |
| `refund_amount` | DECIMAL(12,2) | NOT NULL, DEFAULT 0 | 應退款金額 |
| `reason` | TEXT | | 退貨原因（來自買家或平台） |
| `requested_at` | TIMESTAMPTZ | | 在平台上提出退貨申請的時間 |
| `items` | JSONB | DEFAULT '[]' | 退貨明細（結構與 orders.items 相同） |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | OMS 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 最後更新時間 |

索引：
- `idx_refund_order` on `(order_id)`
- `idx_refund_merchant` on `(merchant_id)`
- `idx_refund_stats` on `(merchant_id, requested_at)` — 用於退款統計查詢

---

### `channel_sync_logs`
用途：通路 API 同步操作的健康與稽核日誌。記錄每次同步嘗試的 HTTP 狀態、請求 / 回應內容及錯誤訊息。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `merchant_id` | VARCHAR(20) | NOT NULL | 所屬商家 |
| `channel_id` | VARCHAR(20) | NOT NULL | 被同步的通路 |
| `sync_type` | VARCHAR(50) | NOT NULL | 同步類型（如 `FETCH_ORDERS`、`SHIP_ORDER`） |
| `http_status` | INTEGER | | 平台 API 回傳的 HTTP 狀態碼 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'success' | 同步結果：`success`、`failure`、`partial` |
| `health` | VARCHAR(20) | | 通路健康評估：`healthy`、`degraded`、`down` |
| `request_payload` | TEXT | | 送出的請求內容（過大時截斷） |
| `response_payload` | TEXT | | 平台 API 回應內容（過大時截斷） |
| `error_message` | TEXT | | 狀態為 `failure` 時的錯誤說明 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 同步嘗試的時間 |

索引：
- `idx_sync_log_channel` on `(channel_id, created_at DESC)`
- `idx_sync_log_merchant` on `(merchant_id, created_at DESC)`

---

### `daily_statistics`
用途：預先彙總的每日業務指標，依 `stat_date` 分區（月分區）。此資料表為儀表板和報表的資料來源。由 `DailyStatisticsService` 使用 Redis dirty-marker 模式進行 upsert。

**複合主鍵**：`(id, stat_date)`——PostgreSQL 聲明式分區的必要條件。
邏輯唯一鍵為 `(merchant_id, platform_id, channel_id, stat_date)`。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK（第一部分）, NOT NULL | NanoID |
| `merchant_id` | VARCHAR(20) | NOT NULL, FK → merchant.id | 所屬商家 |
| `platform_id` | VARCHAR(20) | NOT NULL, FK → platform.id | 平台 |
| `channel_id` | VARCHAR(20) | NOT NULL, FK → channel.id | 通路 |
| `stat_date` | DATE | PK（第二部分）, NOT NULL | 統計日期 |
| `new_order_count` | INTEGER | DEFAULT 0 | **業務視角**：當日建立的訂單數（依 `channel_created_at`） |
| `new_order_amount` | NUMERIC(15,2) | DEFAULT 0 | **業務視角**：新訂單總金額 |
| `gross_order_count` | INTEGER | DEFAULT 0 | **營收視角**：排除取消的訂單數 |
| `gross_amount` | NUMERIC(15,2) | DEFAULT 0 | **營收視角**：排除取消的總金額 |
| `received_count` | INTEGER | DEFAULT 0 | **財務視角**：狀態為 `confirmed` 或以上的訂單數 |
| `received_amount` | NUMERIC(15,2) | DEFAULT 0 | **財務視角**：已收款訂單總金額 |
| `refund_count` | INTEGER | DEFAULT 0 | 當日退款筆數 |
| `refund_amount` | NUMERIC(15,2) | DEFAULT 0 | 總退款金額 |
| `net_amount` | NUMERIC(15,2) | DEFAULT 0 | `received_amount - refund_amount` |
| `shipped_count` | INTEGER | DEFAULT 0 | **物流視角**：當日出貨訂單數 |
| `completed_count` | INTEGER | DEFAULT 0 | 已完成（已送達）訂單數 |
| `cancelled_count` | INTEGER | DEFAULT 0 | 已取消訂單數 |
| `item_sold_count` | INTEGER | DEFAULT 0 | 總銷售件數 |
| `created_at` | TIMESTAMPTZ | DEFAULT now() | 記錄建立時間 |
| `updated_at` | TIMESTAMPTZ | DEFAULT now() | 最後更新時間 |

索引：
- `idx_daily_stats_unique` UNIQUE on `(merchant_id, platform_id, channel_id, stat_date)`

**分區**（月分區，2026 年）：
`daily_statistics_2026_01` 至 `daily_statistics_2026_12`，以及 `daily_statistics_default`（用於明確範圍以外的資料）。

---

### `failed_task_logs`
用途：Kafka 死信訊息的持久化存儲。當訊息到達 `task.dlt` 時由 `DltConsumer` 填入。用於人工調查、重放和稽核。

| 欄位 | 型別 | 約束 | 說明 |
|--------|------|-------------|-------------|
| `id` | VARCHAR(20) | PK, NOT NULL | NanoID 主鍵 |
| `message_id` | VARCHAR(50) | | Kafka 訊息 key 或 request ID |
| `task_type` | VARCHAR(50) | | 失敗訊息中的 `header.taskType` |
| `task_action` | VARCHAR(100) | | 任務類型內的子動作 |
| `source_job_type` | VARCHAR(50) | | 產生訊息的服務名稱 |
| `merchant_id` | VARCHAR(20) | | `header.merchantId`（用於租戶範圍查詢） |
| `owner_id` | VARCHAR(50) | | 正在處理的訂單 ID 或資源 ID |
| `original_topic` | VARCHAR(100) | | 訊息原本發布至的 Kafka topic |
| `original_key` | VARCHAR(200) | | 原始訊息的 Kafka partition key |
| `error_message` | TEXT | | 例外訊息或錯誤說明 |
| `reason` | VARCHAR(50) | | 失敗原因分類：`UNSUPPORTED_VERSION`、`MALFORMED_MESSAGE`、`MAX_RETRIES` 等 |
| `retry_count` | INTEGER | DEFAULT 0 | 進入 DLT 前的重試次數 |
| `payload` | JSONB | | 完整原始訊息內容（用於重放） |
| `created_at` | TIMESTAMPTZ | DEFAULT now() | DLT 記錄建立時間 |

索引：
- `idx_failed_task_created` on `(created_at)` — 用於時間範圍查詢
- `idx_failed_task_action` on `(task_action)` — 用於依任務類型過濾
