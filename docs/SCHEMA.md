# SimpleEC OMS — 資料庫 Schema 設計

> 版本: v3（2026-02-09 重新設計）
> 依據: 現有 01-original-schema.sql + 02-new-tables.sql + 業務需求修正

---

## 表關係總覽

```
platform_account            ← SimpleEC 平台管理員（獨立）
global_config               ← 系統設定（獨立）

merchant                    ← 商家（公司）
  ├── account               ← 商家的操作帳號（多人）
  ├── category              ← 商家的商品分類
  ├── merchant_options      ← 商家自訂選項
  │
  ├── product_group         ← 商品群組（前端管理用，共享描述/圖片/品牌）
  │     └── product         ← 商品 = SKU 級別（倉庫的一個位置）
  │           └── product_barcode  ← 多條碼（多國產線）
  │
  ├── daily_statistics      ← 每日統計（partition by stat_date）
  └── failed_task_logs      ← Kafka 失敗任務 LOG

platform                    ← 平台（momo/shopee/yahoo/pchome）
  └── channel_api_versions  ← 平台 API 版本管理

channel                     ← 通路/館（樞紐：FK merchant + FK platform）
  ├── sell_pack             ← 賣場檔（product × channel 的上架映射）
  ├── orders                ← 訂單（一筆 record = 一張訂單，明細 JSONB）
  │     ├── order_status_logs    ← 狀態變更記錄
  │     ├── order_shipments      ← 出貨物流
  │     └── refund_orders        ← 退款單（退款明細 JSONB）
  └── channel_sync_logs     ← 同步/健康檢查記錄
```

---

## 一、帳號與設定

### platform_account — SimpleEC 平台管理員

> SimpleEC 自己的管理員帳號，用於後台管理所有商家。與 merchant 無關。

```sql
CREATE TABLE public.platform_account (
    id              VARCHAR(50)   NOT NULL,
    name            VARCHAR(50)   NOT NULL,
    email           VARCHAR(256)  NOT NULL,
    password        VARCHAR(512)  NOT NULL,
    create_time     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_time     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT platform_account_email UNIQUE (email)
);
```

### global_config — 系統設定

> VIP 等級限制、全域開關等。獨立表，不屬於任何 merchant。

```sql
CREATE TABLE public.global_config (
    id          VARCHAR(128)   NOT NULL,
    data        VARCHAR(2048)  NOT NULL,
    description VARCHAR(256),
    create_time TIMESTAMPTZ    NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
```

---

## 二、商家體系

### merchant — 商家（公司）

> 系統的租戶。所有業務資料都掛在 merchant 下。

```sql
CREATE TABLE public.merchant (
    merchant_id          VARCHAR(20)  NOT NULL,
    merchant_name        VARCHAR(256) NOT NULL,
    merchant_email       VARCHAR(256) NOT NULL,
    merchant_phone_number VARCHAR(20) NOT NULL,
    tax_id_number        VARCHAR(20)  NOT NULL,     -- 統編
    address_city         VARCHAR(50)  NOT NULL,
    address_region       VARCHAR(50)  NOT NULL,
    address_country      VARCHAR(50)  NOT NULL,
    address_zip          VARCHAR(10)  NOT NULL,
    address_phone_number VARCHAR(20)  NOT NULL,
    address_line1        VARCHAR(256) NOT NULL,
    address_line2        VARCHAR(256) NOT NULL,
    vip_level            INTEGER      NOT NULL DEFAULT 0,
    user_local_time_zone VARCHAR(50)  NOT NULL DEFAULT 'UTC',  -- 商家時區
    payer_name           VARCHAR(256) NOT NULL,
    payer_email          VARCHAR(256) NOT NULL,
    payer_phone_number   VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,     -- enable/disable/suspended
    insert_account_id    VARCHAR(20),
    insert_time          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modified_account_id  VARCHAR(20),
    modified_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id)
);
```

### account — 商家的操作帳號

> 一個 merchant 可以有多個操作人員帳號。is_main_account 標記主帳號。

```sql
CREATE TABLE public.account (
    account_id       VARCHAR(20)  NOT NULL,
    account_name     VARCHAR(256) NOT NULL,
    account_email    VARCHAR(256) NOT NULL,
    account_password VARCHAR(512) NOT NULL,
    account_tel      VARCHAR(20),
    is_main_account  BOOLEAN      NOT NULL DEFAULT false,
    access_level     INTEGER      NOT NULL DEFAULT 0,
    merchant_id      VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'enable',
    totp_secret      VARCHAR(20)  NOT NULL,
    totp_modified_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    insert_account_id  VARCHAR(20),
    insert_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modified_account_id VARCHAR(20),
    modified_time    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id),
    CONSTRAINT account_email_un UNIQUE (account_email),
    CONSTRAINT fk_account_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) ON UPDATE CASCADE ON DELETE NO ACTION
);
```

**關係: merchant 1 ──→ N account**

---

## 三、平台與通路

### platform — 平台

> 第三方電商平台。credential1/credential2 是平台級的 API 認證（各平台用途不同）。

```sql
CREATE TABLE public.platform (
    platform_id      VARCHAR(20)   NOT NULL,
    platform_name    VARCHAR(50)   NOT NULL,
    credential1      VARCHAR(4096),              -- API Key / App ID 等（各平台不同）
    credential2      VARCHAR(4096),              -- API Secret 等（各平台不同）
    actived          BOOLEAN       NOT NULL DEFAULT true,
    queue_topic      VARCHAR(128),               -- Kafka topic 前綴
    currency         VARCHAR(3)    DEFAULT 'TWD',
    ship_options     JSON,                       -- 出貨選項（平台級）
    insert_account_id  VARCHAR(20),
    insert_time      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_account_id VARCHAR(20),
    modified_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (platform_id)
);
```

### channel — 通路/館（核心樞紐）

> **channel 同時 FK 到 merchant 和 platform。**
> 代表「這個商家在這個平台上的這個館」。
> 例：商家 A 在 momo 開了「冷凍館」和「一般館」= 2 個 channel。

```sql
CREATE TABLE public.channel (
    channel_id       VARCHAR(20)   NOT NULL,
    platform_id      VARCHAR(20)   NOT NULL,
    merchant_id      VARCHAR(20)   NOT NULL,
    channel_sn       VARCHAR(128),               -- 通路編號（平台方給的）
    channel_name     VARCHAR(256),               -- 通路名稱
    multi_spec       BOOLEAN       NOT NULL DEFAULT false, -- 該館是否為多規商品
    token            VARCHAR(4096) NOT NULL,     -- 館級 Token1
    token2           VARCHAR(4096),
    token3           VARCHAR(4096),
    token4           VARCHAR(4096),
    token5           VARCHAR(4096),
    actived          BOOLEAN       NOT NULL DEFAULT true,   -- 是否啟用
    write_actived    BOOLEAN       NOT NULL DEFAULT false,  -- 是否啟用寫入
    enable_sync      BOOLEAN       NOT NULL DEFAULT false,  -- 是否啟用同步
    first_sync_start_time TIMESTAMPTZ,
    first_sync_end_time   TIMESTAMPTZ,
    last_sync_time   TIMESTAMPTZ,
    insert_account_id  VARCHAR(20),
    insert_time      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_account_id VARCHAR(20),
    modified_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id),
    CONSTRAINT fk_channel_platform FOREIGN KEY (platform_id)
        REFERENCES public.platform (platform_id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_channel_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) ON UPDATE CASCADE ON DELETE NO ACTION
);
```

**關係:**
- **platform 1 ──→ N channel**（一個平台下多個通路）
- **merchant 1 ──→ N channel**（一個商家開多個通路）
- channel 是 platform 和 merchant 的交叉點

### channel_api_versions — 平台 API 版本管理

```sql
CREATE TABLE public.channel_api_versions (
    id            BIGSERIAL    NOT NULL,
    platform_id   VARCHAR(20)  NOT NULL,
    api_version   VARCHAR(20)  NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    effective_date DATE,
    description   TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_api_version_platform FOREIGN KEY (platform_id)
        REFERENCES public.platform (platform_id) ON UPDATE CASCADE ON DELETE NO ACTION
);
```

---

## 四、商品體系

### product_group — 商品群組

> **前端管理用。** 把相同商品的不同規格（顏色/尺寸）綁成一組，共享描述、圖片、品牌。
> 群組本身不是庫存單位，不出現在訂單裡，不出現在 sell_pack 裡。

```sql
CREATE TABLE public.product_group (
    id            BIGSERIAL    NOT NULL,
    merchant_id   VARCHAR(20)  NOT NULL,
    group_name    VARCHAR(512) NOT NULL,        -- 群組名稱（如「養生雞精禮盒」）
    description   TEXT,                         -- 共享描述
    brand         VARCHAR(100),                 -- 共享品牌
    main_image_url VARCHAR(1024),               -- 共享主圖
    category_id   VARCHAR(20),                  -- 分類（可選）
    status        VARCHAR(20)  NOT NULL DEFAULT 'active', -- active/inactive/deleted
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_product_group_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_product_group_merchant ON public.product_group (merchant_id);
```

### product — 商品 = SKU 級別

> **一個 product = 倉庫的一個位置 = 一個 SKU。**
> item_number 是 SKU 編號，merchant 內唯一。
> 可選掛到 product_group（群組），也可以不掛（獨立商品）。

```sql
CREATE TABLE public.product (
    id              BIGSERIAL     NOT NULL,
    merchant_id     VARCHAR(20)   NOT NULL,
    product_group_id BIGINT,                    -- 所屬群組（可 null = 獨立商品）
    item_number     VARCHAR(100)  NOT NULL,     -- SKU 編號（商家內唯一）
    name            VARCHAR(512)  NOT NULL,     -- 商品名稱（如「養生雞精禮盒 60ml×12入」）
    spec_summary    VARCHAR(256),               -- 規格摘要（如「60ml×12入」「紅色/L」）
    cost_price      DECIMAL(12,2),              -- 成本價
    suggest_price   DECIMAL(12,2),              -- 建議售價
    quantity        INTEGER       NOT NULL DEFAULT 0, -- 庫存數量
    safety_quantity INTEGER       NOT NULL DEFAULT 0, -- 安全庫存量
    status          VARCHAR(20)   NOT NULL DEFAULT 'active', -- active/inactive/deleted
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_product_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_product_group FOREIGN KEY (product_group_id)
        REFERENCES public.product_group (id) ON UPDATE CASCADE ON DELETE SET NULL
);

CREATE UNIQUE INDEX idx_product_merchant_item ON public.product (merchant_id, item_number);
CREATE INDEX idx_product_merchant_status ON public.product (merchant_id, status);
CREATE INDEX idx_product_group ON public.product (product_group_id);
```

**關係:**
- **merchant 1 ──→ N product**
- **product_group 1 ──→ N product**（可選，null = 獨立商品）

### product_barcode — 商品多條碼

> 一個 product 可能從多國產線出來，有不同的條碼。

```sql
CREATE TABLE public.product_barcode (
    id          BIGSERIAL    NOT NULL,
    product_id  BIGINT       NOT NULL,
    barcode     VARCHAR(50)  NOT NULL,
    label       VARCHAR(100),                   -- 標記（如「台灣廠」「日本廠」「EAN-13」）
    is_primary  BOOLEAN      NOT NULL DEFAULT false, -- 主要條碼
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_barcode_product FOREIGN KEY (product_id)
        REFERENCES public.product (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_barcode_product ON public.product_barcode (product_id);
CREATE INDEX idx_barcode_value ON public.product_barcode (barcode);
```

**關係: product 1 ──→ N product_barcode**

---

## 五、賣場檔（商品 × 通路的上架映射）

### sell_pack — 賣場檔

> **sell_pack = 一個 product 在一個 channel 上的上架記錄。**
> 同一個 product（SKU 相同）在不同平台上架，名字/價格/庫存可能不同。
> channel_product_id + channel_spec_id 是平台端的辨識碼。

```sql
CREATE TABLE public.sell_pack (
    id                   BIGSERIAL     NOT NULL,
    merchant_id          VARCHAR(20)   NOT NULL,    -- 所屬商家（必要，非冗餘）
    product_id           BIGINT        NOT NULL,    -- FK → product（我方 SKU）
    channel_id           VARCHAR(20)   NOT NULL,    -- FK → channel（哪個通路）
    channel_product_id   VARCHAR(256),              -- 平台商品編號（賣編）
    channel_spec_id      VARCHAR(256),              -- 平台規格編號
    channel_product_name VARCHAR(512),              -- 平台上顯示的商品名
    channel_spec_name    VARCHAR(256),              -- 平台上顯示的規格名
    channel_product_url  VARCHAR(1024),             -- 平台商品頁 URL
    title                VARCHAR(512),              -- 我方自訂的標題
    selling_price        DECIMAL(12,2),             -- 通路售價
    quantity             INTEGER       NOT NULL DEFAULT 0, -- 通路庫存
    status               VARCHAR(20)   NOT NULL DEFAULT 'draft', -- draft/pending/active/inactive/failed
    last_sync_at         TIMESTAMPTZ,               -- 最後同步時間
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_sellpack_product FOREIGN KEY (product_id)
        REFERENCES public.product (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_sellpack_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (channel_id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_sellpack_merchant ON public.sell_pack (merchant_id);
CREATE INDEX idx_sellpack_channel ON public.sell_pack (channel_id);
CREATE INDEX idx_sellpack_product ON public.sell_pack (product_id);
CREATE UNIQUE INDEX idx_sellpack_upsert_key
    ON public.sell_pack (channel_id, channel_product_id, COALESCE(channel_spec_id, ''));
```

**關係:**
- **product 1 ──→ N sell_pack**（同一個 SKU 在多個通路上架）
- **channel 1 ──→ N sell_pack**（一個通路上架多個商品）
- 唯一約束: `(channel_id, channel_product_id, COALESCE(channel_spec_id, ''))` — 同通路+同賣編+同規格 = 一筆

---

## 六、訂單體系

### orders — 訂單

> **一筆 record = 一張訂單。商品明細用 JSONB 存。**
> 不拆 order_items 表 — 訂單是平台來的完整資料，一起存一起取。

```sql
CREATE TABLE public.orders (
    id                BIGSERIAL     NOT NULL,
    merchant_id       VARCHAR(20)   NOT NULL,       -- 所屬商家
    channel_id        VARCHAR(20)   NOT NULL,       -- 來源通路
    channel_order_id  VARCHAR(100)  NOT NULL,       -- 平台訂單編號
    order_status      VARCHAR(20)   NOT NULL DEFAULT 'pending',
        -- pending/confirmed/processing/shipped/delivered/completed/cancelled/refunding/refunded
    buyer_name        VARCHAR(256),
    buyer_phone       VARCHAR(50),
    buyer_email       VARCHAR(256),
    shipping_address  TEXT,
    shipping_method   VARCHAR(50),
    payment_method    VARCHAR(50),
    total_amount      DECIMAL(12,2) NOT NULL DEFAULT 0,
    shipping_fee      DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    items             JSONB         NOT NULL DEFAULT '[]',  -- 商品明細（見下方結構）
    channel_created_at TIMESTAMPTZ,                 -- 平台端建立時間
    paid_at           TIMESTAMPTZ,
    shipped_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (channel_id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE UNIQUE INDEX idx_order_channel_order ON public.orders (channel_id, channel_order_id);
CREATE INDEX idx_order_merchant_status ON public.orders (merchant_id, order_status);
CREATE INDEX idx_order_created ON public.orders (created_at DESC);
CREATE INDEX idx_order_items ON public.orders USING GIN (items);  -- JSONB GIN 索引
```

**orders.items JSONB 結構:**

```json
[
  {
    "channelProductId":   "MOMO-SKU-98765",
    "channelSpecId":      "MOMO-SPEC-98765-A",
    "channelProductName": "MOMO養生雞精禮盒限定組",
    "channelSpecName":    "60ml×12入(單盒)",
    "skuCode":            "HGJ-60-12",
    "productName":        "MOMO養生雞精禮盒限定組",
    "specInfo":           "60ml×12入(單盒)",
    "quantity":           2,
    "unitPrice":          790.00,
    "subtotal":           1580.00,
    "sellPackId":         12345,
    "productId":          67
  }
]
```

> `sellPackId` 和 `productId` 在訂單入庫時嘗試用 `channelProductId + channelSpecId` 查 sell_pack 填入。
> 查不到就存 null — 訂單先入庫，商品同步後再補。

**關係:**
- **channel 1 ──→ N orders**
- 唯一約束: `(channel_id, channel_order_id)` — 同通路+同平台訂單號 = 一筆

### order_status_logs — 訂單狀態變更記錄

```sql
CREATE TABLE public.order_status_logs (
    id          BIGSERIAL    NOT NULL,
    order_id    BIGINT       NOT NULL,
    from_status VARCHAR(20),                    -- 原狀態（新訂單時 null）
    to_status   VARCHAR(20)  NOT NULL,          -- 新狀態
    operator    VARCHAR(100),                   -- 操作者（SYSTEM / 帳號名）
    remark      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_status_log_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_order_status_log_order ON public.order_status_logs (order_id);
```

### order_shipments — 出貨物流

> 設計預留。出貨是獨立大議題（分包/物流商/封箱/揀貨），後續擴充。

```sql
CREATE TABLE public.order_shipments (
    id                BIGSERIAL    NOT NULL,
    order_id          BIGINT       NOT NULL,
    tracking_number   VARCHAR(100),
    logistics_company VARCHAR(100),
    shipping_status   VARCHAR(20)  NOT NULL DEFAULT 'pending',
    shipped_at        TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_shipment_order ON public.order_shipments (order_id);
CREATE INDEX idx_shipment_tracking ON public.order_shipments (tracking_number);
```

### refund_orders — 退款單

> 一筆退款 = 一筆 record。退款明細用 JSONB 存（同 orders 設計理念）。

```sql
CREATE TABLE public.refund_orders (
    id              BIGSERIAL     NOT NULL,
    order_id        BIGINT        NOT NULL,     -- 原訂單
    merchant_id     VARCHAR(20)   NOT NULL,     -- 所屬商家
    channel_refund_id VARCHAR(100),             -- 平台退款編號
    refund_status   VARCHAR(20)   NOT NULL DEFAULT 'pending',
    refund_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    reason          TEXT,
    items           JSONB         DEFAULT '[]', -- 退款明細（哪些商品退了多少）
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_refund_order ON public.refund_orders (order_id);
CREATE INDEX idx_refund_merchant ON public.refund_orders (merchant_id);
```

**refund_orders.items JSONB 結構:**

```json
[
  {
    "channelProductId": "MOMO-SKU-98765",
    "channelSpecId":    "MOMO-SPEC-98765-A",
    "productName":      "MOMO養生雞精禮盒限定組",
    "quantity":         1,
    "refundAmount":     790.00
  }
]
```

---

## 七、LOG 與統計

### channel_sync_logs — 通路同步/健康檢查記錄

```sql
CREATE TABLE public.channel_sync_logs (
    id               BIGSERIAL    NOT NULL,
    merchant_id      VARCHAR(20)  NOT NULL,
    channel_id       VARCHAR(20)  NOT NULL,
    sync_type        VARCHAR(50)  NOT NULL,     -- FETCH_ORDERS / FETCH_PRODUCTS / MODIFY_PRICE / CHECK_HEALTH / ...
    status           VARCHAR(20)  NOT NULL DEFAULT 'success', -- success / failed
    health           VARCHAR(20),               -- OK / TOKEN_INVALID / API_DOWN（CHECK_HEALTH 時用）
    request_payload  TEXT,
    response_payload TEXT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_sync_log_channel ON public.channel_sync_logs (channel_id, created_at DESC);
CREATE INDEX idx_sync_log_merchant ON public.channel_sync_logs (merchant_id, created_at DESC);
```

### daily_statistics — 每日統計

```sql
CREATE TABLE public.daily_statistics (
    id              BIGSERIAL,
    merchant_id     VARCHAR(20)   NOT NULL,
    channel_id      VARCHAR(20),                -- null = 全通路加總
    stat_date       DATE          NOT NULL,
    order_count     INTEGER       DEFAULT 0,
    total_amount    NUMERIC(15,2) DEFAULT 0,
    shipped_count   INTEGER       DEFAULT 0,
    completed_count INTEGER       DEFAULT 0,
    cancelled_count INTEGER       DEFAULT 0,
    refund_count    INTEGER       DEFAULT 0,
    created_at      TIMESTAMPTZ   DEFAULT now(),
    updated_at      TIMESTAMPTZ   DEFAULT now(),
    PRIMARY KEY (id, stat_date)
) PARTITION BY RANGE (stat_date);

CREATE UNIQUE INDEX idx_daily_stats_unique
    ON public.daily_statistics (merchant_id, stat_date, channel_id);
```

### failed_task_logs — Kafka 失敗任務 LOG

```sql
CREATE TABLE public.failed_task_logs (
    id              BIGSERIAL    PRIMARY KEY,
    message_id      VARCHAR(50),
    task_type       VARCHAR(50),
    task_action     VARCHAR(100),
    source_job_type VARCHAR(50),
    merchant_id     VARCHAR(20),
    owner_id        VARCHAR(50),
    original_topic  VARCHAR(100),
    original_key    VARCHAR(200),
    error_message   TEXT,
    reason          VARCHAR(50),
    retry_count     INTEGER      DEFAULT 0,
    payload         JSONB,
    created_at      TIMESTAMPTZ  DEFAULT now()
);

CREATE INDEX idx_failed_task_created ON public.failed_task_logs (created_at);
CREATE INDEX idx_failed_task_action ON public.failed_task_logs (task_action);
```

---

## 八、關係圖（FK 完整列表）

```
merchant ─┬──→ account              (merchant_id)
          ├──→ category             (merchant_id)
          ├──→ merchant_options     (merchant_id)
          ├──→ product_group        (merchant_id)
          ├──→ product              (merchant_id)
          └──→ channel              (merchant_id)

platform ─┬──→ channel              (platform_id)
          └──→ channel_api_versions (platform_id)

product_group ──→ product            (product_group_id, 可 null)

product  ─┬──→ product_barcode      (product_id, CASCADE DELETE)
          └──→ sell_pack            (product_id)

channel  ─┬──→ sell_pack            (channel_id)
          └──→ orders               (channel_id)

orders   ─┬──→ order_status_logs    (order_id, CASCADE DELETE)
          ├──→ order_shipments      (order_id, CASCADE DELETE)
          └──→ refund_orders        (order_id, NO ACTION)
```

### PK 類型規則

| PK 類型 | 適用表 | 原因 |
|---------|--------|------|
| `VARCHAR(20)` | merchant, account, platform, channel, category | 舊系統沿用，業務手動編號 |
| `BIGSERIAL` | product, product_group, product_barcode, sell_pack, orders, 所有 log/統計表 | 新系統自增 |

### merchant_id 存在原則

> **所有業務資料表都必須有 merchant_id**，明確標示資料歸屬。
> 只有 platform, global_config, platform_account 等「系統級」表不需要。

| 有 merchant_id | 表 |
|---------------|---|
| ✅ 必須 | account, category, product_group, product, sell_pack, orders, refund_orders, daily_statistics, channel, channel_sync_logs, failed_task_logs |
| ❌ 不需要 | platform, platform_account, global_config, channel_api_versions |
| ⚠️ 可推導但仍保留 | sell_pack（可從 channel 推導）、orders（可從 channel 推導）— 保留是因為查詢效率，避免每次都 JOIN channel |

---

## 九、vs 舊 schema 的主要差異

| 項目 | 舊 schema | 新 schema | 理由 |
|------|----------|----------|------|
| 訂單明細 | order_items 獨立表 | orders.items JSONB | 一筆訂單一筆 record，減少 JOIN，不會因拆表產生冗餘 |
| 退款明細 | refund_order_items 獨立表 | refund_orders.items JSONB | 同上 |
| 商品規格 | product_spec 獨立表 | **移除**。product = SKU 級別 | 不同 spec 在倉庫就是不同位置、不同 SKU = 不同 product |
| 商品群組 | 無 | **新增 product_group** | 前端管理用，把同類 product 綁一起共享描述/圖片 |
| 多條碼 | product.barcode 單欄位 | **新增 product_barcode 表** | 一個 product 可能多國產線、多條碼 |
| sell_pack 唯一約束 | 無 | **新增 UNIQUE** (channel_id, channel_product_id, channel_spec_id) | 防止同步時重複建立 |
| sell_pack 平台欄位 | 只有 channel_product_id | **新增** channel_spec_id, channel_product_name, channel_spec_name | 平台需要雙 ID + 雙 Name |
| order_items 平台 ID | 無 | **移除 order_items 表**，平台 ID 存在 orders.items JSONB 裡 | channelProductId/channelSpecId 跟著訂單走 |

---

## 十、需要刪除的舊表

```
DROP TABLE IF EXISTS public.refund_order_items;  -- 退款明細 → refund_orders.items JSONB
DROP TABLE IF EXISTS public.order_items;          -- 訂單明細 → orders.items JSONB
DROP TABLE IF EXISTS public.product_spec;         -- 商品規格 → product = SKU 級別
```

需要新建:
```
CREATE TABLE public.product_group;    -- 商品群組
CREATE TABLE public.product_barcode;  -- 商品多條碼
```
