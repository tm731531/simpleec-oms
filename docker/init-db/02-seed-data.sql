-- =============================================================================
-- SimpleEC OMS — Seed Data for Development & Testing
-- Version: v4 (2026-02-09)
--
-- Scenario: A health food company selling on momo and shopee.
--   - 1 merchant, 2 accounts (main + sub)
--   - 4 platforms (momo, shopee, yahoo, pchome)
--   - 2 channels (momo frozen, shopee health)
--   - 1 product group (chicken essence series)
--   - 3 products (2 in group + 1 standalone)
--   - 4 barcodes, 4 sell_packs
--   - 3 orders (completed, shipped, pending)
--   - 1 refund order
--   - 5 status logs, 2 sync logs, 3 daily stats
--
-- NanoIDs use readable prefixes for easy debugging.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- platform_account
-- ---------------------------------------------------------------------------
INSERT INTO public.platform_account (id, name, email, password)
VALUES ('pa_admin_001', 'SimpleEC Admin', 'admin@simpleec.com',
        '$2a$10$dummyhashfordevonly000000000000000000000000000000');

-- ---------------------------------------------------------------------------
-- global_config
-- ---------------------------------------------------------------------------
INSERT INTO public.global_config (id, data, description) VALUES
('vip_10_limits', '{"max_channels": 50, "max_products": 10000}', 'VIP 10 resource limits'),
('vip_0_limits',  '{"max_channels": 3, "max_products": 100}',    'Free tier resource limits'),
('encryption_master_key',
 'ZVNzMksyaDVNWGR2ZVZSc1ZVSnJUVWxTVjFJdk9Xb3hhVUUwV0dkNE9FMTRjM3B1Y0hKbE5saFpSVDA5',
 'AES-256-GCM master key (3xBase64). DEV ONLY — replace in production.');

-- ---------------------------------------------------------------------------
-- merchant
-- ---------------------------------------------------------------------------
INSERT INTO public.merchant (
    id, merchant_name, merchant_email, merchant_phone_number,
    tax_id_number, address_city, address_region, address_country,
    address_zip, address_phone_number, address_line1, address_line2,
    vip_level, user_local_time_zone,
    payer_name, payer_email, payer_phone_number, status
) VALUES (
    'm_test_001', '養生食品有限公司', 'contact@health-food.com.tw', '02-28881234',
    '12345678', '台北市', '大安區', '台灣',
    '106', '02-28881234', '忠孝東路四段100號', '5樓',
    5, 'Asia/Taipei',
    '王大明', 'billing@health-food.com.tw', '0912345678', 'active'
);

-- ---------------------------------------------------------------------------
-- account
-- ---------------------------------------------------------------------------
INSERT INTO public.account (
    id, account_name, account_email, account_password,
    account_tel, is_main_account, access_level, merchant_id, status
) VALUES
('a_main_001', '王大明', 'wang@health-food.com.tw',
 '$2a$10$dummyhashfordevonly000000000000000000000000000000',
 '0912345678', true, 9, 'm_test_001', 'enable'),
('a_sub_001', '李小華', 'lee@health-food.com.tw',
 '$2a$10$dummyhashfordevonly000000000000000000000000000000',
 '0923456789', false, 5, 'm_test_001', 'enable');

-- ---------------------------------------------------------------------------
-- merchant_options
-- ---------------------------------------------------------------------------
INSERT INTO public.merchant_options (id, merchant_id, name, type, status) VALUES
('mo_001', 'm_test_001', '宅配到府',   'shipping', 'enable'),
('mo_002', 'm_test_001', '超商取貨',   'shipping', 'enable'),
('mo_003', 'm_test_001', '信用卡',     'payment',  'enable');

-- ---------------------------------------------------------------------------
-- platform
-- ---------------------------------------------------------------------------
INSERT INTO public.platform (id, platform_name, actived, queue_topic, currency) VALUES
('momo',    'momo購物',     true,  'task.channel.momo',    'TWD'),
('shopee',  'Shopee蝦皮',   true,  'task.channel.shopee',  'TWD'),
('yahoo',   'Yahoo奇摩',    true,  'task.channel.yahoo',   'TWD'),
('pchome',  'PChome商店街', false, 'task.channel.pchome',  'TWD');

-- ---------------------------------------------------------------------------
-- channel_api_versions
-- ---------------------------------------------------------------------------
INSERT INTO public.channel_api_versions (id, platform_id, api_version, is_active, effective_date, description) VALUES
('cav_momo_v3',   'momo',   'v3', true,  '2026-01-01', 'momo API v3 (current)'),
('cav_shopee_v2', 'shopee', 'v2', true,  '2025-06-01', 'Shopee Open Platform v2');

-- ---------------------------------------------------------------------------
-- channel
-- ---------------------------------------------------------------------------
INSERT INTO public.channel (
    id, platform_id, merchant_id, channel_sn, channel_name,
    multi_spec, token, actived, write_actived, enable_sync
) VALUES
('ch_momo_001', 'momo', 'm_test_001', 'MOMO-FROZEN-2024',
 'momo 冷凍養生館', false,
 'momo-api-token-placeholder-dev', true, true, true),
('ch_shopee_001', 'shopee', 'm_test_001', 'SHOPEE-HEALTH-2024',
 'Shopee 保健品旗艦店', true,
 'shopee-api-token-placeholder-dev', true, false, true);

-- ---------------------------------------------------------------------------
-- product_group
-- ---------------------------------------------------------------------------
INSERT INTO public.product_group (
    id, merchant_id, group_name, description, brand, main_image_url, status
) VALUES (
    'pg_chicken_01', 'm_test_001', '雞精系列',
    '嚴選土雞慢火燉煮，滴雞精禮盒組合',
    '養生堂', 'https://example.com/images/chicken-essence.jpg', 'active'
);

-- ---------------------------------------------------------------------------
-- product (3 products: 2 in group, 1 standalone)
-- ---------------------------------------------------------------------------
INSERT INTO public.product (
    id, merchant_id, product_group_id, sku, name, spec_summary,
    cost_price, suggest_price, quantity, safety_quantity, status
) VALUES
('pd_hgj6012', 'm_test_001', 'pg_chicken_01', 'HGJ-60-12',
 '養生雞精禮盒 60ml×12入', '60ml×12入',
 350.00, 790.00, 200, 20, 'active'),
('pd_hgj606', 'm_test_001', 'pg_chicken_01', 'HGJ-60-6',
 '養生雞精禮盒 60ml×6入', '60ml×6入',
 180.00, 420.00, 150, 15, 'active'),
('pd_greentea', 'm_test_001', NULL, 'GTP-100',
 '有機綠茶粉 100g', '100g 罐裝',
 120.00, 350.00, 80, 10, 'active');

-- ---------------------------------------------------------------------------
-- product_barcode
-- ---------------------------------------------------------------------------
INSERT INTO public.product_barcode (id, product_id, barcode, label, is_primary) VALUES
('pb_001', 'pd_hgj6012', '4710001234567', '台灣廠 EAN-13',  true),
('pb_002', 'pd_hgj6012', '4710001234574', '日本廠 EAN-13',  false),
('pb_003', 'pd_hgj606',  '4710002345678', '台灣廠 EAN-13',  true),
('pb_004', 'pd_greentea', '4710009876543', '台灣廠 EAN-13', true);

-- ---------------------------------------------------------------------------
-- sell_pack (3 on momo + 1 on shopee)
-- ---------------------------------------------------------------------------
INSERT INTO public.sell_pack (
    id, merchant_id, product_id, channel_id, sku,
    channel_product_id, channel_spec_id, channel_product_name, channel_spec_name,
    channel_product_url, title, selling_price, quantity, status
) VALUES
('sp_momo_001', 'm_test_001', 'pd_hgj6012', 'ch_momo_001', 'HGJ-60-12',
 'MOMO-SKU-98765', 'MOMO-SPEC-98765-A', 'MOMO養生雞精禮盒限定組', '60ml×12入(單盒)',
 'https://www.momoshop.com.tw/goods/98765', '雞精12入禮盒', 790.00, 200, 'active'),
('sp_momo_002', 'm_test_001', 'pd_hgj606', 'ch_momo_001', 'HGJ-60-6',
 'MOMO-SKU-98766', NULL, 'MOMO養生雞精隨身組', NULL,
 'https://www.momoshop.com.tw/goods/98766', '雞精6入隨身組', 420.00, 150, 'active'),
('sp_momo_003', 'm_test_001', 'pd_greentea', 'ch_momo_001', 'GTP-100',
 'MOMO-SKU-55555', NULL, 'MOMO有機綠茶粉嚴選', NULL,
 'https://www.momoshop.com.tw/goods/55555', '有機綠茶粉', 350.00, 80, 'active'),
('sp_shopee_001', 'm_test_001', 'pd_hgj6012', 'ch_shopee_001', 'HGJ-60-12',
 'SHOPEE-ITEM-112233', 'SHOPEE-VAR-A1', '養生堂滴雞精12入經典組', '經典12入',
 'https://shopee.tw/product/112233', '蝦皮雞精12入', 820.00, 100, 'active');

-- ---------------------------------------------------------------------------
-- orders (3 orders: completed, shipped, pending)
-- ---------------------------------------------------------------------------
INSERT INTO public.orders (
    id, merchant_id, channel_id, channel_order_id, order_status,
    buyer_name, buyer_phone, buyer_email, shipping_address,
    shipping_method, payment_method, total_amount, shipping_fee, discount_amount,
    items, channel_created_at, paid_at, shipped_at
) VALUES
-- Order 1: completed
('ord_001', 'm_test_001', 'ch_momo_001', 'MOMO-ORD-20260201-001', 'completed',
 '陳小明', '0911222333', 'chen@example.com', '台北市信義區松高路1號',
 '宅配', '信用卡', 1580.00, 0.00, 0.00,
 '[{"sku":"HGJ-60-12","channelProductId":"MOMO-SKU-98765","channelSpecId":"MOMO-SPEC-98765-A","channelProductName":"MOMO養生雞精禮盒限定組","channelSpecName":"60ml×12入(單盒)","productName":"養生雞精禮盒 60ml×12入","quantity":2,"unitPrice":790.00,"subtotal":1580.00,"sellPackId":"sp_momo_001","productId":"pd_hgj6012"}]'::jsonb,
 '2026-02-01 10:30:00+08', '2026-02-01 10:35:00+08', '2026-02-02 14:00:00+08'),
-- Order 2: shipped
('ord_002', 'm_test_001', 'ch_momo_001', 'MOMO-ORD-20260205-002', 'shipped',
 '林美麗', '0922333444', 'lin@example.com', '新北市板橋區中山路100號',
 '宅配', '貨到付款', 770.00, 60.00, 0.00,
 '[{"sku":"HGJ-60-6","channelProductId":"MOMO-SKU-98766","channelSpecId":null,"channelProductName":"MOMO養生雞精隨身組","channelSpecName":null,"productName":"養生雞精禮盒 60ml×6入","quantity":1,"unitPrice":420.00,"subtotal":420.00,"sellPackId":"sp_momo_002","productId":"pd_hgj606"},{"sku":"GTP-100","channelProductId":"MOMO-SKU-55555","channelSpecId":null,"channelProductName":"MOMO有機綠茶粉嚴選","channelSpecName":null,"productName":"有機綠茶粉 100g","quantity":1,"unitPrice":350.00,"subtotal":350.00,"sellPackId":"sp_momo_003","productId":"pd_greentea"}]'::jsonb,
 '2026-02-05 15:20:00+08', '2026-02-05 15:25:00+08', '2026-02-06 09:00:00+08'),
-- Order 3: pending
('ord_003', 'm_test_001', 'ch_shopee_001', 'SHOPEE-ORD-20260208-001', 'pending',
 '張大華', '0933444555', 'chang@example.com', '台中市西屯區台灣大道四段200號',
 '超商取貨', '信用卡', 820.00, 0.00, 0.00,
 '[{"sku":"HGJ-60-12","channelProductId":"SHOPEE-ITEM-112233","channelSpecId":"SHOPEE-VAR-A1","channelProductName":"養生堂滴雞精12入經典組","channelSpecName":"經典12入","productName":"養生雞精禮盒 60ml×12入","quantity":1,"unitPrice":820.00,"subtotal":820.00,"sellPackId":"sp_shopee_001","productId":"pd_hgj6012"}]'::jsonb,
 '2026-02-08 20:15:00+08', '2026-02-08 20:16:00+08', NULL);

-- ---------------------------------------------------------------------------
-- order_status_logs
-- ---------------------------------------------------------------------------
INSERT INTO public.order_status_logs (id, order_id, from_status, to_status, operator, remark) VALUES
('osl_001', 'ord_001', NULL,       'pending',   'system',   '訂單建立'),
('osl_002', 'ord_001', 'pending',  'shipped',   'a_main_001', '已出貨 - 黑貓宅急便'),
('osl_003', 'ord_001', 'shipped',  'completed', 'system',   '買家確認收貨'),
('osl_004', 'ord_002', NULL,       'pending',   'system',   '訂單建立'),
('osl_005', 'ord_002', 'pending',  'shipped',   'a_main_001', '已出貨 - 新竹物流');

-- ---------------------------------------------------------------------------
-- order_shipments
-- ---------------------------------------------------------------------------
INSERT INTO public.order_shipments (
    id, order_id, tracking_number, logistics_company, shipping_status, shipped_at
) VALUES
('os_001', 'ord_001', 'BK123456789TW', '黑貓宅急便', 'delivered', '2026-02-02 14:00:00+08'),
('os_002', 'ord_002', 'HCT987654321',  '新竹物流',   'in_transit', '2026-02-06 09:00:00+08');

-- ---------------------------------------------------------------------------
-- refund_orders
-- ---------------------------------------------------------------------------
INSERT INTO public.refund_orders (
    id, order_id, merchant_id, channel_refund_id,
    refund_status, refund_amount, reason, items
) VALUES (
    'rf_001', 'ord_001', 'm_test_001', 'MOMO-REFUND-001',
    'approved', 790.00, '商品瑕疵，退一盒',
    '[{"sku":"HGJ-60-12","channelProductId":"MOMO-SKU-98765","channelSpecId":"MOMO-SPEC-98765-A","productName":"MOMO養生雞精禮盒限定組","quantity":1,"refundAmount":790.00,"sellPackId":"sp_momo_001","productId":"pd_hgj6012"}]'::jsonb
);

-- ---------------------------------------------------------------------------
-- channel_sync_logs
-- ---------------------------------------------------------------------------
INSERT INTO public.channel_sync_logs (
    id, merchant_id, channel_id, sync_type, status, health
) VALUES
('csl_001', 'm_test_001', 'ch_momo_001',   'FETCH_PRODUCTS', 'success', 'healthy'),
('csl_002', 'm_test_001', 'ch_shopee_001', 'FETCH_ORDERS',   'success', 'healthy');

-- ---------------------------------------------------------------------------
-- daily_statistics (2026-02-08)
-- ---------------------------------------------------------------------------
INSERT INTO public.daily_statistics (
    id, merchant_id, platform_id, channel_id, stat_date,
    order_count, total_amount, shipped_count, completed_count, cancelled_count, refund_count
) VALUES
('ds_001', 'm_test_001', 'momo',   'ch_momo_001',   '2026-02-08', 2, 2350.00, 1, 1, 0, 1),
('ds_002', 'm_test_001', 'shopee', 'ch_shopee_001', '2026-02-08', 1, 820.00,  0, 0, 0, 0),
('ds_003', 'm_test_001', '_ALL_',  '_ALL_',         '2026-02-08', 3, 3170.00, 1, 1, 0, 1);

-- ---------------------------------------------------------------------------
-- channel_shipping_mapping
-- ---------------------------------------------------------------------------

-- MOMO shipping mappings
INSERT INTO public.channel_shipping_mapping (id, merchant_id, channel_id, platform_shipping_method, logistics_company, logistics_company_code, description, active)
VALUES
    ('csp_momo_001', 'm_test_001', 'ch_momo_001', 'HOME_DELIVERY', '黑貓宅急便', 'BLACKCAT', 'Momo home delivery via Black Cat', true),
    ('csp_momo_002', 'm_test_001', 'ch_momo_001', 'STORE_PICKUP', 'Momo 超商取貨', 'MOMO_STORE', 'Momo convenience store pickup', true),
    ('csp_momo_003', 'm_test_001', 'ch_momo_001', 'SEVEN_ELEVEN', '7-ELEVEN', '7ELV', 'Momo 7-Eleven pickup', true)
ON CONFLICT (channel_id, platform_shipping_method) DO NOTHING;

-- Shopee shipping mappings
INSERT INTO public.channel_shipping_mapping (id, merchant_id, channel_id, platform_shipping_method, logistics_company, logistics_company_code, description, active)
VALUES
    ('csp_shopee_001', 'm_test_001', 'ch_shopee_001', 'STANDARD_DELIVERY', '新竹物流', 'XINDE', 'Shopee standard delivery', true),
    ('csp_shopee_002', 'm_test_001', 'ch_shopee_001', 'EXPRESS_DELIVERY', '黑貓宅急便', 'BLACKCAT', 'Shopee express delivery', true),
    ('csp_shopee_003', 'm_test_001', 'ch_shopee_001', 'SELF_PICKUP', 'Shopee 自取', 'SHOPEE_SELF', 'Shopee self pickup', true)
ON CONFLICT (channel_id, platform_shipping_method) DO NOTHING;
