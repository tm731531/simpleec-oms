# SimpleEC OMS — 資料庫 Schema 設計

> 版本: v4（2026-02-09）
>
> **PK 規則: 所有表的 PK 都是 VARCHAR(20)，程式端用 NanoID 產生。**

---

## 表關係總覽

```
platform_account            ← SimpleEC 平台管理員（獨立）
global_config               ← 系統設定（獨立）

merchant                    ← 商家（公司）
  ├── account               ← 商家的操作帳號（多人）
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
    id              VARCHAR(20)   NOT NULL,     -- NanoID
    name            VARCHAR(50)   NOT NULL,
    email           VARCHAR(256)  NOT NULL,
    password        VARCHAR(512)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT platform_account_email UNIQUE (email)
);
```

### global_config — 系統設定

> VIP 等級限制、全域開關等。獨立表，不屬於任何 merchant。

```sql
CREATE TABLE public.global_config (
    id          VARCHAR(128)   NOT NULL,         -- 設定 key（如 'vip_10_limits'）
    data        VARCHAR(2048)  NOT NULL,
    description VARCHAR(256),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
```

---

## 二、商家體系

### merchant — 商家（公司）

> 系統的租戶。所有業務資料都掛在 merchant 下。

```sql
CREATE TABLE public.merchant (
    id                   VARCHAR(20)  NOT NULL,  -- NanoID
    merchant_name        VARCHAR(256) NOT NULL,
    merchant_email       VARCHAR(256) NOT NULL,
    merchant_phone_number VARCHAR(20) NOT NULL,
    tax_id_number        VARCHAR(20)  NOT NULL,
    address_city         VARCHAR(50)  NOT NULL,
    address_region       VARCHAR(50)  NOT NULL,
    address_country      VARCHAR(50)  NOT NULL,
    address_zip          VARCHAR(10)  NOT NULL,
    address_phone_number VARCHAR(20)  NOT NULL,
    address_line1        VARCHAR(256) NOT NULL,
    address_line2        VARCHAR(256) NOT NULL,
    vip_level            INTEGER      NOT NULL DEFAULT 0,
    user_local_time_zone VARCHAR(50)  NOT NULL DEFAULT 'UTC',
    payer_name           VARCHAR(256) NOT NULL,
    payer_email          VARCHAR(256) NOT NULL,
    payer_phone_number   VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
```

### account — 商家的操作帳號

> 一個 merchant 可以有多個操作人員帳號。is_main_account 標記主帳號。

```sql
CREATE TABLE public.account (
    id               VARCHAR(20)  NOT NULL,      -- NanoID
    account_name     VARCHAR(256) NOT NULL,
    account_email    VARCHAR(256) NOT NULL,
    account_password VARCHAR(512) NOT NULL,
    account_tel      VARCHAR(20),
    is_main_account  BOOLEAN      NOT NULL DEFAULT false,
    access_level     INTEGER      NOT NULL DEFAULT 0,
    merchant_id      VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'enable',
    totp_secret      VARCHAR(20),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT account_email_un UNIQUE (account_email),
    CONSTRAINT fk_account_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION
);
```

**關係: merchant 1 ──→ N account**

---

## 三、平台與通路

### platform — 平台

> 第三方電商平台。credential1/credential2 是平台級的 API 認證（各平台用途不同）。

```sql
CREATE TABLE public.platform (
    id               VARCHAR(20)   NOT NULL,     -- NanoID（或直接用 'momo', 'shopee' 等）
    platform_name    VARCHAR(50)   NOT NULL,
    credential1      VARCHAR(4096),
    credential2      VARCHAR(4096),
    actived          BOOLEAN       NOT NULL DEFAULT true,
    queue_topic      VARCHAR(128),
    currency         VARCHAR(3)    DEFAULT 'TWD',
    ship_options     JSON,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
```

### channel — 通路/館（核心樞紐）

> **channel 同時 FK 到 merchant 和 platform。**
> 代表「這個商家在這個平台上的這個館」。
> 例：商家 A 在 momo 開了「冷凍館」和「一般館」= 2 個 channel。

```sql
CREATE TABLE public.channel (
    id               VARCHAR(20)   NOT NULL,     -- NanoID
    platform_id      VARCHAR(20)   NOT NULL,
    merchant_id      VARCHAR(20)   NOT NULL,
    channel_sn       VARCHAR(128),               -- 通路編號（平台方給的）
    channel_name     VARCHAR(256),
    multi_spec       BOOLEAN       NOT NULL DEFAULT false,
    token            VARCHAR(4096) NOT NULL,
    token2           VARCHAR(4096),
    token3           VARCHAR(4096),
    token4           VARCHAR(4096),
    token5           VARCHAR(4096),
    actived          BOOLEAN       NOT NULL DEFAULT true,
    write_actived    BOOLEAN       NOT NULL DEFAULT false,
    enable_sync      BOOLEAN       NOT NULL DEFAULT false,
    first_sync_start_time TIMESTAMPTZ,
    first_sync_end_time   TIMESTAMPTZ,
    last_sync_time   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_channel_platform FOREIGN KEY (platform_id)
        REFERENCES public.platform (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_channel_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION
);
```

**關係:**
- **platform 1 ──→ N channel**
- **merchant 1 ──→ N channel**
- channel 是 platform 和 merchant 的交叉點

### channel_api_versions — 平台 API 版本管理

```sql
CREATE TABLE public.channel_api_versions (
    id             VARCHAR(20)  NOT NULL,        -- NanoID
    platform_id    VARCHAR(20)  NOT NULL,
    api_version    VARCHAR(20)  NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT true,
    effective_date DATE,
    description    TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_api_version_platform FOREIGN KEY (platform_id)
        REFERENCES public.platform (id) ON UPDATE CASCADE ON DELETE NO ACTION
);
```

---

## 四、商品體系

### product_group — 商品群組

> **前端管理用。** 把相同商品的不同規格（顏色/尺寸）綁成一組，共享描述、圖片、品牌。
> 群組本身不是庫存單位，不出現在訂單裡，不出現在 sell_pack 裡。

```sql
CREATE TABLE public.product_group (
    id             VARCHAR(20)  NOT NULL,        -- NanoID
    merchant_id    VARCHAR(20)  NOT NULL,
    group_name     VARCHAR(512) NOT NULL,
    description    TEXT,
    brand          VARCHAR(100),
    main_image_url VARCHAR(1024),
    status         VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_product_group_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_product_group_merchant ON public.product_group (merchant_id);
```

### product — 商品 = SKU 級別

> **一個 product = 倉庫的一個位置 = 一個 SKU。**
> sku 是 SKU 編號，merchant 內唯一。
> 可選掛到 product_group（群組），也可以不掛（獨立商品）。

```sql
CREATE TABLE public.product (
    id               VARCHAR(20)   NOT NULL,     -- NanoID
    merchant_id      VARCHAR(20)   NOT NULL,
    product_group_id VARCHAR(20),                -- 所屬群組（可 null = 獨立商品）
    sku              VARCHAR(100)  NOT NULL,     -- SKU 編號（商家內唯一）
    name             VARCHAR(512)  NOT NULL,     -- 商品名稱（如「養生雞精禮盒 60ml×12入」）
    spec_summary     VARCHAR(256),               -- 規格摘要（如「60ml×12入」「紅色/L」）
    cost_price       DECIMAL(12,2),
    suggest_price    DECIMAL(12,2),
    quantity         INTEGER       NOT NULL DEFAULT 0,
    safety_quantity  INTEGER       NOT NULL DEFAULT 0,
    status           VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_product_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_product_group FOREIGN KEY (product_group_id)
        REFERENCES public.product_group (id) ON UPDATE CASCADE ON DELETE SET NULL
);

CREATE UNIQUE INDEX idx_product_merchant_sku ON public.product (merchant_id, sku);
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
    id          VARCHAR(20)  NOT NULL,           -- NanoID
    product_id  VARCHAR(20)  NOT NULL,
    barcode     VARCHAR(50)  NOT NULL,
    label       VARCHAR(100),                    -- 標記（如「台灣廠」「日本廠」「EAN-13」）
    is_primary  BOOLEAN      NOT NULL DEFAULT false,
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
>
> **sku 是我方 product 的 SKU 編號** — 冗餘存在此表，確保從平台抓賣場回來後
> 能直接用 sku match product，不需要先查 product_id 再反推。

```sql
CREATE TABLE public.sell_pack (
    id                   VARCHAR(20)   NOT NULL, -- NanoID
    merchant_id          VARCHAR(20)   NOT NULL, -- 所屬商家
    product_id           VARCHAR(20)   NOT NULL, -- FK → product（我方 SKU）
    channel_id           VARCHAR(20)   NOT NULL, -- FK → channel（哪個通路）
    sku                  VARCHAR(100)  NOT NULL, -- 我方 SKU 編號（= product.sku）
    channel_product_id   VARCHAR(256),           -- 平台商品編號（賣編）
    channel_spec_id      VARCHAR(256),           -- 平台規格編號
    channel_product_name VARCHAR(512),           -- 平台上顯示的商品名
    channel_spec_name    VARCHAR(256),           -- 平台上顯示的規格名
    channel_product_url  VARCHAR(1024),          -- 平台商品頁 URL
    title                VARCHAR(512),           -- 我方自訂的標題
    selling_price        DECIMAL(12,2),          -- 通路售價
    quantity             INTEGER       NOT NULL DEFAULT 0,
    status               VARCHAR(20)   NOT NULL DEFAULT 'draft',
    last_sync_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_sellpack_product FOREIGN KEY (product_id)
        REFERENCES public.product (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_sellpack_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_sellpack_merchant ON public.sell_pack (merchant_id);
CREATE INDEX idx_sellpack_channel ON public.sell_pack (channel_id);
CREATE INDEX idx_sellpack_product ON public.sell_pack (product_id);
CREATE INDEX idx_sellpack_sku ON public.sell_pack (sku);
CREATE UNIQUE INDEX idx_sellpack_upsert_key
    ON public.sell_pack (channel_id, channel_product_id, COALESCE(channel_spec_id, ''));
```

**關係:**
- **product 1 ──→ N sell_pack**（同一個 SKU 在多個通路上架）
- **channel 1 ──→ N sell_pack**（一個通路上架多個商品）
- 唯一約束: `(channel_id, channel_product_id, COALESCE(channel_spec_id, ''))` — 同通路+同賣編+同規格 = 一筆

**平台同步時的 match 流程:**
```
平台回傳 channelProductId + channelSpecId
  → 查 sell_pack（已存在 → update）
  → sell_pack.sku → 查 product（by merchant_id + sku）
  → 找到 → sell_pack.product_id = product.id
  → 找不到 → 自動建立 product（sku 從平台取）
```

---

## 六、訂單體系

### orders — 訂單

> **一筆 record = 一張訂單。商品明細用 JSONB 存。**

```sql
CREATE TABLE public.orders (
    id                VARCHAR(20)   NOT NULL,    -- NanoID
    merchant_id       VARCHAR(20)   NOT NULL,
    channel_id        VARCHAR(20)   NOT NULL,
    channel_order_id  VARCHAR(100)  NOT NULL,    -- 平台訂單編號
    order_status      VARCHAR(20)   NOT NULL DEFAULT 'pending',
    buyer_name        VARCHAR(512),              -- ★ AES-256-GCM 加密（Base64 密文較長）
    buyer_phone       VARCHAR(256),              -- ★ AES-256-GCM 加密
    buyer_email       VARCHAR(512),              -- ★ AES-256-GCM 加密
    shipping_address  TEXT,                      -- ★ AES-256-GCM 加密
    shipping_method   VARCHAR(50),
    payment_method    VARCHAR(50),
    total_amount      DECIMAL(12,2) NOT NULL DEFAULT 0,
    shipping_fee      DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    refund_amount     DECIMAL(12,2) NOT NULL DEFAULT 0,  -- 累計退款金額（每次退貨累加）
    has_refund        BOOLEAN       NOT NULL DEFAULT false, -- 是否有退貨（快速篩選）
    items             JSONB         NOT NULL DEFAULT '[]',
    channel_created_at TIMESTAMPTZ,
    paid_at           TIMESTAMPTZ,
    shipped_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE UNIQUE INDEX idx_order_channel_order ON public.orders (channel_id, channel_order_id);
CREATE INDEX idx_order_merchant_status ON public.orders (merchant_id, order_status);
CREATE INDEX idx_order_created ON public.orders (created_at DESC);
CREATE INDEX idx_order_items ON public.orders USING GIN (items);
CREATE INDEX idx_order_has_refund ON public.orders (merchant_id, has_refund) WHERE has_refund = true;
```

**★ PII 加密說明：**

> `buyer_name`, `buyer_phone`, `buyer_email`, `shipping_address` 四個欄位在 application 層做 AES-256-GCM 加密。
> Master key 存在 `global_config` 表（key=`encryption_master_key`，三次 Base64 編碼）。
> 透過 PBKDF2 + merchantId 鹽衍生 per-merchant AES key。
> MyBatis TypeHandler (`EncryptedFieldTypeHandler`) 透明加解密，Java code 層看到明文。
> 欄位加寬是因為 Base64(nonce+ciphertext+tag) 比明文長約 1.5~2x。
> API 列表回傳遮罩值（王\*明、0912\*\*\*678），詳情/匯出回傳完整明文。

**orders.items JSONB 結構:**

```json
[
  {
    "sku":                "HGJ-60-12",
    "channelProductId":   "MOMO-SKU-98765",
    "channelSpecId":      "MOMO-SPEC-98765-A",
    "channelProductName": "MOMO養生雞精禮盒限定組",
    "channelSpecName":    "60ml×12入(單盒)",
    "productName":        "MOMO養生雞精禮盒限定組",
    "quantity":           2,
    "unitPrice":          790.00,
    "subtotal":           1580.00,
    "sellPackId":         "sp_abc123",
    "productId":          "pd_xyz789"
  }
]
```

> `sellPackId` 和 `productId` 在訂單入庫時嘗試用 `channelProductId + channelSpecId` 查 sell_pack 填入。
> 查不到就存 null — 訂單先入庫，商品同步後再補。

**關係: channel 1 ──→ N orders**

### order_status_logs — 訂單狀態變更記錄

```sql
CREATE TABLE public.order_status_logs (
    id          VARCHAR(20)  NOT NULL,           -- NanoID
    order_id    VARCHAR(20)  NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20)  NOT NULL,
    operator    VARCHAR(100),
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
    id                VARCHAR(20)  NOT NULL,     -- NanoID
    order_id          VARCHAR(20)  NOT NULL,
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

> 一筆退款 = 一筆 record。退款明細用 JSONB 存。

```sql
CREATE TABLE public.refund_orders (
    id                VARCHAR(20)   NOT NULL,    -- NanoID
    order_id          VARCHAR(20)   NOT NULL,
    merchant_id       VARCHAR(20)   NOT NULL,
    channel_refund_id VARCHAR(100),
    refund_status     VARCHAR(20)   NOT NULL DEFAULT 'pending',
    refund_amount     DECIMAL(12,2) NOT NULL DEFAULT 0,
    reason            TEXT,
    items             JSONB         DEFAULT '[]',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
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
    "sku":              "HGJ-60-12",
    "channelProductId": "MOMO-SKU-98765",
    "channelSpecId":    "MOMO-SPEC-98765-A",
    "productName":      "MOMO養生雞精禮盒限定組",
    "quantity":         1,
    "refundAmount":     790.00,
    "sellPackId":       "sp_abc123",
    "productId":        "pd_xyz789"
  }
]
```

---

## 七、LOG 與統計

### channel_sync_logs — 通路同步/健康檢查記錄

```sql
CREATE TABLE public.channel_sync_logs (
    id               VARCHAR(20)  NOT NULL,      -- NanoID
    merchant_id      VARCHAR(20)  NOT NULL,
    channel_id       VARCHAR(20)  NOT NULL,
    sync_type        VARCHAR(50)  NOT NULL,
    http_status      INTEGER,                    -- HTTP response code from channel API
    status           VARCHAR(20)  NOT NULL DEFAULT 'success',
    health           VARCHAR(20),
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

> **channel_id 不用 null 表示全通路。**
> 全通路加總用 `platform_id` 欄位：填 platform_id 表示該平台全通路，不用 null 就能走 index。

```sql
CREATE TABLE public.daily_statistics (
    id              VARCHAR(20),                 -- NanoID
    merchant_id     VARCHAR(20)   NOT NULL,
    platform_id     VARCHAR(20)   NOT NULL,      -- 平台（全通路加總時只到平台級）
    channel_id      VARCHAR(20)   NOT NULL,      -- 通路（全平台加總時填 '_ALL_'）
    stat_date       DATE          NOT NULL,

    -- ★ 業務視角：當日新增訂單（不分狀態，只看 created_at 在當天的）
    new_order_count     INTEGER       DEFAULT 0,
    new_order_amount    NUMERIC(15,2) DEFAULT 0,

    -- ★ 老闆視角：營業額（排除 cancelled 的全部訂單）
    gross_order_count   INTEGER       DEFAULT 0,
    gross_amount        NUMERIC(15,2) DEFAULT 0,

    -- ★ 財務視角：實收（confirmed 以上狀態）- 退款
    received_count      INTEGER       DEFAULT 0,
    received_amount     NUMERIC(15,2) DEFAULT 0,
    refund_count        INTEGER       DEFAULT 0,     -- 退款筆數（refund_orders 當日新增）
    refund_amount       NUMERIC(15,2) DEFAULT 0,     -- 退款金額
    net_amount          NUMERIC(15,2) DEFAULT 0,     -- 淨收 = received - refund

    -- ★ 物流視角：各狀態計數
    shipped_count       INTEGER       DEFAULT 0,
    completed_count     INTEGER       DEFAULT 0,
    cancelled_count     INTEGER       DEFAULT 0,

    -- ★ 商品統計
    item_sold_count     INTEGER       DEFAULT 0,     -- 售出件數

    created_at      TIMESTAMPTZ   DEFAULT now(),
    updated_at      TIMESTAMPTZ   DEFAULT now(),
    PRIMARY KEY (id, stat_date)
) PARTITION BY RANGE (stat_date);

CREATE UNIQUE INDEX idx_daily_stats_unique
    ON public.daily_statistics (merchant_id, platform_id, channel_id, stat_date);
```

> 詳細聚合規則與多角色視角定義見 [docs/STATISTICS_DESIGN.md](STATISTICS_DESIGN.md)。

**統計粒度:**
- 每通路: `merchant_id='M001', platform_id='momo', channel_id='CH-MOMO-001'`
- 全平台: `merchant_id='M001', platform_id='momo', channel_id='_ALL_'`
- 全商家: `merchant_id='M001', platform_id='_ALL_', channel_id='_ALL_'`

> 不用 null — 全部走 index。

### failed_task_logs — Kafka 失敗任務 LOG

```sql
CREATE TABLE public.failed_task_logs (
    id              VARCHAR(20)  NOT NULL,       -- NanoID
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
    created_at      TIMESTAMPTZ  DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_failed_task_created ON public.failed_task_logs (created_at);
CREATE INDEX idx_failed_task_action ON public.failed_task_logs (task_action);
```

---

## 八、關係圖（FK 完整列表）

```
merchant ─┬──→ account              (merchant_id)
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

### PK 規則

> **所有表的 PK 都是 `VARCHAR(20)`，程式端用 NanoID 產生。**
> 不用 BIGSERIAL / auto-increment。NanoID 在 Java 端產生後寫入。

### 欄位命名一致性

> **同樣意義的欄位，全系統用同一個名字：**

| 意義 | 統一欄位名 | 出現在 |
|------|-----------|--------|
| 我方 SKU 編號 | `sku` | product.sku, sell_pack.sku, orders.items[].sku, refund_orders.items[].sku |
| 平台商品編號 | `channel_product_id` / `channelProductId` | sell_pack, orders.items[], refund_orders.items[] |
| 平台規格編號 | `channel_spec_id` / `channelSpecId` | sell_pack, orders.items[], refund_orders.items[] |
| 平台商品名 | `channel_product_name` / `channelProductName` | sell_pack, orders.items[] |
| 平台規格名 | `channel_spec_name` / `channelSpecName` | sell_pack, orders.items[] |
| 賣場檔 ID | `sell_pack_id` / `sellPackId` | orders.items[], refund_orders.items[] |
| 商品 ID | `product_id` / `productId` | sell_pack, orders.items[], refund_orders.items[] |

### merchant_id 存在原則

> **所有業務資料表都必須有 merchant_id。**
> 只有 platform, global_config, platform_account 等「系統級」表不需要。

| 需要 merchant_id | 表 |
|-----------------|---|
| ✅ 必須 | account, product_group, product, sell_pack, orders, refund_orders, daily_statistics, channel, channel_sync_logs, failed_task_logs, merchant_options |
| ❌ 不需要 | platform, platform_account, global_config, channel_api_versions |

---

## 九、vs 舊 schema 的主要差異

| 項目 | 舊 schema | 新 schema | 理由 |
|------|----------|----------|------|
| PK | 混用 VARCHAR + BIGSERIAL | **全部 VARCHAR(20) NanoID** | 程式端統一產生，分散式友好 |
| 訂單明細 | order_items 獨立表 | orders.items **JSONB** | 一筆訂單一筆 record |
| 退款明細 | refund_order_items 獨立表 | refund_orders.items **JSONB** | 同上，且帶 sellPackId + productId 做統計 |
| 商品規格 | product_spec 獨立表 | **移除** | 不同 spec = 不同 SKU = 不同 product |
| 商品群組 | 無 | **新增 product_group** | 前端管理用，綁相同商品共享描述/圖片 |
| 多條碼 | product.barcode 單欄位 | **新增 product_barcode 表** | 多國產線 = 多條碼 |
| SKU 欄位名 | item_number | **統一為 sku** | 全系統一致：product.sku, sell_pack.sku, JSONB 裡都叫 sku |
| sell_pack 缺 sku | 無 sku 欄位 | **新增 sku** | 從平台抓賣場後必須靠 sku 對到 product |
| sell_pack 唯一約束 | 無 | **新增 UNIQUE** | 防止同步時重複建立 |
| 統計 null | channel_id null = 全通路 | **channel_id='\_ALL\_'** + **新增 platform_id** | null 走不了 index |
| category | 有 | **移除** | 不需要 |
| 退款 JSONB 缺 ID | 無 sellPackId/productId | **新增** | 統計退款商品時需要 |

---

## 十、需要刪除的舊表

```sql
DROP TABLE IF EXISTS public.refund_order_items;  -- → refund_orders.items JSONB
DROP TABLE IF EXISTS public.order_items;          -- → orders.items JSONB
DROP TABLE IF EXISTS public.product_spec;         -- → product = SKU 級別
DROP TABLE IF EXISTS public.category;             -- 移除
```

需要新建:
```sql
CREATE TABLE public.product_group;     -- 商品群組
CREATE TABLE public.product_barcode;   -- 商品多條碼
```
