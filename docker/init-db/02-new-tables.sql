-- =============================================
-- SimpleEC OMS - 新增資料表 (原 Solr 資料遷移至 PostgreSQL)
-- Product, SellPack, Order 及相關表
-- =============================================

-- ===== 商品 (Product) =====
CREATE TABLE public.product
(
    id BIGSERIAL NOT NULL,
    merchant_id character varying(20) NOT NULL,
    item_number character varying(100) NOT NULL,
    name character varying(512) NOT NULL,
    description text,
    brand character varying(100),
    main_image_url character varying(1024),
    cost_price decimal(12,2),
    suggest_price decimal(12,2),
    total_quantity integer NOT NULL DEFAULT 0,
    safety_quantity integer NOT NULL DEFAULT 0,
    status character varying(20) NOT NULL DEFAULT 'active',
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_product_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE UNIQUE INDEX idx_product_merchant_item ON public.product (merchant_id, item_number);
CREATE INDEX idx_product_merchant_status ON public.product (merchant_id, status);

COMMENT ON TABLE public.product IS '商品主檔';
COMMENT ON COLUMN public.product.merchant_id IS '所屬商家';
COMMENT ON COLUMN public.product.item_number IS '商品貨號(SKU)';
COMMENT ON COLUMN public.product.name IS '商品名稱';
COMMENT ON COLUMN public.product.description IS '商品描述';
COMMENT ON COLUMN public.product.brand IS '品牌';
COMMENT ON COLUMN public.product.main_image_url IS '主圖URL';
COMMENT ON COLUMN public.product.cost_price IS '成本價';
COMMENT ON COLUMN public.product.suggest_price IS '建議售價';
COMMENT ON COLUMN public.product.total_quantity IS '總庫存數量';
COMMENT ON COLUMN public.product.safety_quantity IS '安全庫存量';
COMMENT ON COLUMN public.product.status IS '狀態: active/inactive/deleted';


-- ===== 商品規格 (Product Spec) =====
CREATE TABLE public.product_spec
(
    id BIGSERIAL NOT NULL,
    product_id bigint NOT NULL,
    sku_code character varying(100),
    spec_name character varying(100) NOT NULL,
    spec_value character varying(256) NOT NULL,
    price decimal(12,2),
    quantity integer NOT NULL DEFAULT 0,
    barcode character varying(50),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_spec_product FOREIGN KEY (product_id)
        REFERENCES public.product (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_product_spec_product ON public.product_spec (product_id);

COMMENT ON TABLE public.product_spec IS '商品規格';
COMMENT ON COLUMN public.product_spec.product_id IS '所屬商品';
COMMENT ON COLUMN public.product_spec.sku_code IS 'SKU編碼';
COMMENT ON COLUMN public.product_spec.spec_name IS '規格名稱(如:顏色/尺寸)';
COMMENT ON COLUMN public.product_spec.spec_value IS '規格值(如:紅色/L)';
COMMENT ON COLUMN public.product_spec.price IS '規格價格';
COMMENT ON COLUMN public.product_spec.quantity IS '規格庫存';
COMMENT ON COLUMN public.product_spec.barcode IS '條碼';


-- ===== 賣場檔 (SellPack - Product mapped to channel listing) =====
CREATE TABLE public.sell_pack
(
    id BIGSERIAL NOT NULL,
    merchant_id character varying(20) NOT NULL,
    product_id bigint NOT NULL,
    channel_id character varying(20) NOT NULL,
    channel_product_id character varying(256),
    channel_product_url character varying(1024),
    title character varying(512),
    selling_price decimal(12,2),
    quantity integer NOT NULL DEFAULT 0,
    status character varying(20) NOT NULL DEFAULT 'draft',
    last_sync_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_sellpack_product FOREIGN KEY (product_id)
        REFERENCES public.product (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_sellpack_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (channel_id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_sellpack_merchant ON public.sell_pack (merchant_id);
CREATE INDEX idx_sellpack_channel ON public.sell_pack (channel_id);
CREATE INDEX idx_sellpack_product ON public.sell_pack (product_id);
CREATE INDEX idx_sellpack_channel_product ON public.sell_pack (channel_id, channel_product_id);

COMMENT ON TABLE public.sell_pack IS '賣場檔 - 商品與通路上架的映射';
COMMENT ON COLUMN public.sell_pack.merchant_id IS '所屬商家';
COMMENT ON COLUMN public.sell_pack.product_id IS '對應商品';
COMMENT ON COLUMN public.sell_pack.channel_id IS '對應通路';
COMMENT ON COLUMN public.sell_pack.channel_product_id IS '通路端商品ID';
COMMENT ON COLUMN public.sell_pack.channel_product_url IS '通路端商品URL';
COMMENT ON COLUMN public.sell_pack.title IS '通路上的商品標題';
COMMENT ON COLUMN public.sell_pack.selling_price IS '售價';
COMMENT ON COLUMN public.sell_pack.quantity IS '通路上的庫存';
COMMENT ON COLUMN public.sell_pack.status IS '狀態: draft/pending/active/inactive/failed';
COMMENT ON COLUMN public.sell_pack.last_sync_at IS '最後同步時間';


-- ===== 訂單 (Orders) =====
CREATE TABLE public.orders
(
    id BIGSERIAL NOT NULL,
    merchant_id character varying(20) NOT NULL,
    channel_id character varying(20) NOT NULL,
    channel_order_id character varying(100) NOT NULL,
    order_status character varying(20) NOT NULL DEFAULT 'pending',
    buyer_name character varying(256),
    buyer_phone character varying(50),
    buyer_email character varying(256),
    shipping_address text,
    shipping_method character varying(50),
    payment_method character varying(50),
    total_amount decimal(12,2) NOT NULL DEFAULT 0,
    shipping_fee decimal(12,2) NOT NULL DEFAULT 0,
    discount_amount decimal(12,2) NOT NULL DEFAULT 0,
    channel_created_at timestamp with time zone,
    paid_at timestamp with time zone,
    shipped_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (channel_id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE UNIQUE INDEX idx_order_channel_order ON public.orders (channel_id, channel_order_id);
CREATE INDEX idx_order_merchant_status ON public.orders (merchant_id, order_status);
CREATE INDEX idx_order_created ON public.orders (created_at DESC);

COMMENT ON TABLE public.orders IS '訂單主檔';
COMMENT ON COLUMN public.orders.merchant_id IS '所屬商家';
COMMENT ON COLUMN public.orders.channel_id IS '來源通路';
COMMENT ON COLUMN public.orders.channel_order_id IS '通路訂單編號';
COMMENT ON COLUMN public.orders.order_status IS '訂單狀態: pending/confirmed/processing/shipped/delivered/completed/cancelled/refunding/refunded';
COMMENT ON COLUMN public.orders.buyer_name IS '買家姓名';
COMMENT ON COLUMN public.orders.buyer_phone IS '買家電話';
COMMENT ON COLUMN public.orders.buyer_email IS '買家信箱';
COMMENT ON COLUMN public.orders.shipping_address IS '收件地址';
COMMENT ON COLUMN public.orders.shipping_method IS '配送方式';
COMMENT ON COLUMN public.orders.payment_method IS '付款方式';
COMMENT ON COLUMN public.orders.total_amount IS '訂單總金額';
COMMENT ON COLUMN public.orders.shipping_fee IS '運費';
COMMENT ON COLUMN public.orders.discount_amount IS '折扣金額';
COMMENT ON COLUMN public.orders.channel_created_at IS '通路端建立時間';
COMMENT ON COLUMN public.orders.paid_at IS '付款時間';
COMMENT ON COLUMN public.orders.shipped_at IS '出貨時間';


-- ===== 訂單明細 (Order Items) =====
CREATE TABLE public.order_items
(
    id BIGSERIAL NOT NULL,
    order_id bigint NOT NULL,
    product_id bigint,
    sell_pack_id bigint,
    sku_code character varying(100),
    product_name character varying(512) NOT NULL,
    spec_info character varying(512),
    quantity integer NOT NULL DEFAULT 1,
    unit_price decimal(12,2) NOT NULL DEFAULT 0,
    subtotal decimal(12,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_order_items_order ON public.order_items (order_id);

COMMENT ON TABLE public.order_items IS '訂單明細';
COMMENT ON COLUMN public.order_items.order_id IS '所屬訂單';
COMMENT ON COLUMN public.order_items.product_id IS '對應商品';
COMMENT ON COLUMN public.order_items.sell_pack_id IS '對應賣場檔';
COMMENT ON COLUMN public.order_items.sku_code IS 'SKU';
COMMENT ON COLUMN public.order_items.product_name IS '商品名稱';
COMMENT ON COLUMN public.order_items.spec_info IS '規格資訊';
COMMENT ON COLUMN public.order_items.quantity IS '數量';
COMMENT ON COLUMN public.order_items.unit_price IS '單價';
COMMENT ON COLUMN public.order_items.subtotal IS '小計';


-- ===== 訂單狀態記錄 (Order Status Logs) =====
CREATE TABLE public.order_status_logs
(
    id BIGSERIAL NOT NULL,
    order_id bigint NOT NULL,
    from_status character varying(20),
    to_status character varying(20) NOT NULL,
    operator character varying(100),
    remark text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_status_log_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_order_status_log_order ON public.order_status_logs (order_id);

COMMENT ON TABLE public.order_status_logs IS '訂單狀態變更記錄';
COMMENT ON COLUMN public.order_status_logs.order_id IS '所屬訂單';
COMMENT ON COLUMN public.order_status_logs.from_status IS '原狀態';
COMMENT ON COLUMN public.order_status_logs.to_status IS '新狀態';
COMMENT ON COLUMN public.order_status_logs.operator IS '操作者';
COMMENT ON COLUMN public.order_status_logs.remark IS '備註';


-- ===== 出貨記錄 (Order Shipments) =====
CREATE TABLE public.order_shipments
(
    id BIGSERIAL NOT NULL,
    order_id bigint NOT NULL,
    tracking_number character varying(100),
    logistics_company character varying(100),
    shipping_status character varying(20) NOT NULL DEFAULT 'pending',
    shipped_at timestamp with time zone,
    delivered_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_shipment_order ON public.order_shipments (order_id);
CREATE INDEX idx_shipment_tracking ON public.order_shipments (tracking_number);

COMMENT ON TABLE public.order_shipments IS '出貨記錄';
COMMENT ON COLUMN public.order_shipments.tracking_number IS '物流追蹤編號';
COMMENT ON COLUMN public.order_shipments.logistics_company IS '物流公司';
COMMENT ON COLUMN public.order_shipments.shipping_status IS '出貨狀態';


-- ===== 退款單 (Refund Orders) =====
CREATE TABLE public.refund_orders
(
    id BIGSERIAL NOT NULL,
    order_id bigint NOT NULL,
    merchant_id character varying(20) NOT NULL,
    channel_refund_id character varying(100),
    refund_status character varying(20) NOT NULL DEFAULT 'pending',
    refund_amount decimal(12,2) NOT NULL DEFAULT 0,
    reason text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_refund_order ON public.refund_orders (order_id);
CREATE INDEX idx_refund_merchant ON public.refund_orders (merchant_id);

COMMENT ON TABLE public.refund_orders IS '退款單';
COMMENT ON COLUMN public.refund_orders.order_id IS '原訂單';
COMMENT ON COLUMN public.refund_orders.channel_refund_id IS '通路退款編號';
COMMENT ON COLUMN public.refund_orders.refund_status IS '退款狀態';
COMMENT ON COLUMN public.refund_orders.refund_amount IS '退款金額';
COMMENT ON COLUMN public.refund_orders.reason IS '退款原因';


-- ===== 退款明細 (Refund Order Items) =====
CREATE TABLE public.refund_order_items
(
    id BIGSERIAL NOT NULL,
    refund_order_id bigint NOT NULL,
    order_item_id bigint NOT NULL,
    quantity integer NOT NULL DEFAULT 1,
    refund_amount decimal(12,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_refund_item_refund FOREIGN KEY (refund_order_id)
        REFERENCES public.refund_orders (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_refund_item_order_item FOREIGN KEY (order_item_id)
        REFERENCES public.order_items (id) ON UPDATE CASCADE ON DELETE NO ACTION
);

COMMENT ON TABLE public.refund_order_items IS '退款明細';


-- ===== 通路同步記錄 (Channel Sync Logs) =====
CREATE TABLE public.channel_sync_logs
(
    id BIGSERIAL NOT NULL,
    merchant_id character varying(20) NOT NULL,
    channel_id character varying(20) NOT NULL,
    sync_type character varying(50) NOT NULL,
    status character varying(20) NOT NULL DEFAULT 'success',
    request_payload text,
    response_payload text,
    error_message text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_sync_log_channel ON public.channel_sync_logs (channel_id, created_at DESC);
CREATE INDEX idx_sync_log_merchant ON public.channel_sync_logs (merchant_id, created_at DESC);

COMMENT ON TABLE public.channel_sync_logs IS '通路同步記錄';
COMMENT ON COLUMN public.channel_sync_logs.sync_type IS '同步類型: fetchOrders/updatePrice/updateQuantity/etc';
COMMENT ON COLUMN public.channel_sync_logs.status IS '狀態: success/failed';
COMMENT ON COLUMN public.channel_sync_logs.request_payload IS '請求內容';
COMMENT ON COLUMN public.channel_sync_logs.response_payload IS '回應內容';
COMMENT ON COLUMN public.channel_sync_logs.error_message IS '錯誤訊息';


-- ===== 通路API版本 (Channel API Versions) =====
CREATE TABLE public.channel_api_versions
(
    id BIGSERIAL NOT NULL,
    platform_id character varying(20) NOT NULL,
    api_version character varying(20) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    effective_date date,
    description text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_api_version_platform FOREIGN KEY (platform_id)
        REFERENCES public.platform (platform_id) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE INDEX idx_api_version_channel ON public.channel_api_versions (platform_id, is_active);

COMMENT ON TABLE public.channel_api_versions IS '通路API版本管理';
COMMENT ON COLUMN public.channel_api_versions.platform_id IS '平台設定';
COMMENT ON COLUMN public.channel_api_versions.api_version IS 'API版本號';
COMMENT ON COLUMN public.channel_api_versions.is_active IS '是否啟用';
COMMENT ON COLUMN public.channel_api_versions.effective_date IS '生效日期';
COMMENT ON COLUMN public.channel_api_versions.description IS '版本說明';


-- ===== 初始通路設定種子資料 =====
INSERT INTO public.platform (platform_id, platform_name, actived, queue_topic) VALUES
('momo', 'momo購物', true, 'channel.action'),
('shopee', '蝦皮購物', true, 'channel.action'),
('yahoo', 'Yahoo購物中心', true, 'channel.action'),
('pchome', 'PChome商店街', true, 'channel.action')
ON CONFLICT (platform_id) DO NOTHING;
