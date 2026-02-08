-- ONEEC OMS - Original Schema (cleaned)

CREATE TABLE public.platform_account
(
    id character varying(50) NOT NULL,
    name character varying(50) NOT NULL,
    email character varying(256) NOT NULL,
    password character varying(512) NOT NULL,
    create_time timestamp with time zone NOT NULL DEFAULT now(),
    update_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT platform_account_email UNIQUE (email)
);
COMMENT ON TABLE public.platform_account IS '平台管理者帳號';

COMMENT ON COLUMN public.platform_account.name IS '名稱';
COMMENT ON COLUMN public.platform_account.email IS '信箱';
COMMENT ON COLUMN public.platform_account.password IS '密碼';
COMMENT ON COLUMN public.platform_account.create_time IS '建立時間';
COMMENT ON COLUMN public.platform_account.update_time IS '修改時間';
	
	
CREATE TABLE public.global_config
(
    id character varying(128) NOT NULL,
    data character varying(2048) NOT NULL,
    description character varying(256),
    create_time timestamp with time zone NOT NULL DEFAULT now(),
    update_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
COMMENT ON TABLE public.global_config IS 'Global Config';

COMMENT ON COLUMN public.global_config.data IS 'Config Data';
COMMENT ON COLUMN public.global_config.description IS '敘述';
INSERT INTO public.global_config (id, "data", description) VALUES('vip_10_limits', '{"channelCount":3,"syncDays":30}', 'Vip 10 Limits');
INSERT INTO public.global_config (id, "data", description) VALUES('vip_30_limits', '{"channelCount":10,"syncDays":60}', 'Vip 30 Limits');
INSERT INTO public.global_config (id, "data", description) VALUES('vip_50_limits', '{"channelCount":20,"syncDays":60}', 'Vip 50 Limits');
INSERT INTO public.global_config (id, "data", description) VALUES('vip_99_limits', '{"channelCount":50,"syncDays":60}', 'Vip 99 Limits');
INSERT INTO public.global_config (id, "data", description) VALUES('vip_200_limits', '{"channelCount":999,"syncDays":60}', 'Vip 200 Limits');
CREATE TABLE public.merchant
(
    merchant_id character varying(20) NOT NULL,
    merchant_name character varying(256) NOT NULL,
    merchant_email character varying(256) NOT NULL,
    merchant_phone_number character varying(20) NOT NULL,
    tax_id_number character varying(20) NOT NULL,
    address_city character varying(50) NOT NULL,
    address_region character varying(50) NOT NULL,
    address_country character varying(50) NOT NULL,
    address_zip character varying(10) NOT NULL,
    address_phone_number character varying(20) NOT NULL,
    address_line1 character varying(256) NOT NULL,
    address_line2 character varying(256) NOT NULL,
    vip_level integer NOT NULL DEFAULT 0,
    user_local_time_zone character varying(50) NOT NULL DEFAULT 'UTC',
    payer_name character varying(256) NOT NULL,
    payer_email character varying(256) NOT NULL,
    payer_phone_number character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20),
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id)
);
COMMENT ON TABLE public.merchant IS '公司';

COMMENT ON COLUMN public.merchant.merchant_name IS '店鋪名稱';
COMMENT ON COLUMN public.merchant.merchant_email IS '信箱';
COMMENT ON COLUMN public.merchant.merchant_phone_number IS '電話';
COMMENT ON COLUMN public.merchant.tax_id_number IS '統編';
COMMENT ON COLUMN public.merchant.address_city IS '地址_城市';
COMMENT ON COLUMN public.merchant.address_region IS '地址_地區';
COMMENT ON COLUMN public.merchant.address_country IS '地址_國家';
COMMENT ON COLUMN public.merchant.address_zip IS '地址_郵遞區碼';
COMMENT ON COLUMN public.merchant.address_phone_number IS '地址_電話';
COMMENT ON COLUMN public.merchant.address_line1 IS '地址';
COMMENT ON COLUMN public.merchant.address_line2 IS '地址';
COMMENT ON COLUMN public.merchant.vip_level IS 'VIP 等級';
COMMENT ON COLUMN public.merchant.user_local_time_zone IS '使用者設定的時區';
COMMENT ON COLUMN public.merchant.payer_name IS '帳務聯絡人姓名';
COMMENT ON COLUMN public.merchant.payer_email IS '帳務聯絡人mail';
COMMENT ON COLUMN public.merchant.payer_phone_number IS '帳務聯絡人電話';
COMMENT ON COLUMN public.merchant.status IS '帳號狀態';
COMMENT ON COLUMN public.merchant.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant.insert_time IS '建檔時間';
COMMENT ON COLUMN public.merchant.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.merchant.modified_time IS '最後修改時間';

--ALTER TABLE merchant  ADD COLUMN user_local_time_zone VARCHAR(50) DEFAULT 'UTC' ;
CREATE TABLE public.account
(
    account_id character varying(20) NOT NULL,
    account_name character varying(256) NOT NULL,
    account_email character varying(256) NOT NULL,
    account_password character varying(512) NOT NULL,
    account_tel character varying(20),
    is_main_account boolean NOT NULL DEFAULT false,
    access_level integer NOT NULL DEFAULT 0,
    merchant_id character varying(20) NOT NULL,
    status character varying(20) NOT NULL DEFAULT 'enable',
    totp_secret character varying(20) NOT NULL,
    totp_modified_time timestamp with time zone NOT NULL DEFAULT now(),
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20),
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id),
    CONSTRAINT account_email_un UNIQUE (account_email),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.account IS '公司帳號';

COMMENT ON COLUMN public.account.account_name IS '名稱';
COMMENT ON COLUMN public.account.account_email IS '信箱';
COMMENT ON COLUMN public.account.account_password IS '密碼';
COMMENT ON COLUMN public.account.account_tel IS '電話';
COMMENT ON COLUMN public.account.is_main_account IS '是否為主帳號';
COMMENT ON COLUMN public.account.access_level IS '權限';
COMMENT ON COLUMN public.account.merchant_id IS '公司的ID';
COMMENT ON COLUMN public.account.status IS '帳號狀態';
COMMENT ON COLUMN public.account.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.account.insert_time IS '建檔時間';
COMMENT ON COLUMN public.account.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.account.modified_time IS '最後修改時間';
COMMENT ON COLUMN public.account.totp_modified_time IS 'TOTP修改時間';

CREATE TABLE public.platform
(
    platform_id character varying(20) NOT NULL,
    platform_name character varying(50) NOT NULL,
    credential1 character varying(4096),
    credential2 character varying(4096),
    actived boolean NOT NULL DEFAULT true,
    queue_topic character varying(128),
    currency character varying(3) DEFAULT 'TWD',
    ship_options json,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20),
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (platform_id)
);
COMMENT ON TABLE public.platform IS '平台';

COMMENT ON COLUMN public.platform.platform_name IS '平台名稱';
COMMENT ON COLUMN public.platform.credential1 IS '平台認證欄位1（通常為 API Key，各平台用途不同）';
COMMENT ON COLUMN public.platform.credential2 IS '平台認證欄位2（通常為 API Secret，各平台用途不同）';
COMMENT ON COLUMN public.platform.actived IS '是否啟用';
COMMENT ON COLUMN public.platform.queue_topic IS 'Queue Topic';
COMMENT ON COLUMN public.platform.currency IS '平台幣別';
COMMENT ON COLUMN public.platform.ship_options IS '出貨選項';
COMMENT ON COLUMN public.platform.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.platform.insert_time IS '建檔時間';
COMMENT ON COLUMN public.platform.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.platform.modified_time IS '最後修改時間';

--ALTER TABLE platform  ADD COLUMN currency varchar(3) DEFAULT 'TWD' ;
--COMMENT ON COLUMN public.platform.currency IS '平台幣別';

--ALTER TABLE public.platform ADD ship_options json NULL;
--COMMENT ON COLUMN public.platform.ship_options IS '出貨選項';

INSERT INTO public.platform
(platform_id, platform_name, actived, queue_topic)
VALUES('shopee', '蝦皮', true, 'action-to-shopee');

INSERT INTO public.platform
(platform_id, platform_name, actived, queue_topic)
VALUES('momo', 'MomoShop', false, 'action-to-momo');

INSERT INTO public.platform
(platform_id, platform_name, actived, queue_topic)
VALUES('yahoo_mall', 'Yahoo購物中心', false, 'action-to-yahoo-mall');

INSERT INTO public.platform
(platform_id, platform_name, actived, queue_topic)
VALUES('et_mall', '東森ETMall', false, 'action-to-et-mall');

INSERT INTO public.platform
(platform_id, platform_name, actived, queue_topic)
VALUES('systex_b300', '精誠B300-EC', false, 'action-to-systex-b300');
CREATE TABLE public.channel
(
    channel_id character varying(20) NOT NULL,
    platform_id character varying(20) NOT NULL,
    channel_sn character varying(128),
    channel_name character varying(256),
    multi_spec boolean NOT NULL DEFAULT false,
    token character varying(4096) NOT NULL,
    token2 character varying(4096),
    token3 character varying(4096),
    token4 character varying(4096),
    token5 character varying(4096),
    merchant_id character varying(20) NOT NULL,
    actived boolean NOT NULL DEFAULT true,
    write_actived boolean NOT NULL DEFAULT false,
    enable_sync boolean NOT NULL DEFAULT false,
    first_sync_start_time timestamp with time zone,
    first_sync_end_time timestamp with time zone,
    last_sync_time timestamp with time zone,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20),
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id),
    CONSTRAINT platform_id_fk FOREIGN KEY (platform_id)
        REFERENCES public.platform (platform_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID,
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.channel IS '公司通路';

COMMENT ON COLUMN public.channel.channel_sn IS '通路編號(通路方)';
COMMENT ON COLUMN public.channel.channel_name IS '通路名稱(通路方)';
COMMENT ON COLUMN public.channel.multi_spec IS '是否為多規商品';
COMMENT ON COLUMN public.channel.token IS '通路所需Token1';
COMMENT ON COLUMN public.channel.token2 IS '通路所需Token2';
COMMENT ON COLUMN public.channel.token3 IS '通路所需Token3';
COMMENT ON COLUMN public.channel.token4 IS '通路所需Token4';
COMMENT ON COLUMN public.channel.token5 IS '通路所需Token5';
COMMENT ON COLUMN public.channel.merchant_id IS '公司的ID';
COMMENT ON COLUMN public.channel.actived IS '是否啟用';
COMMENT ON COLUMN public.channel.write_actived IS '是否啟用通路資料寫入';
COMMENT ON COLUMN public.channel.enable_sync IS '是否啟用資料同步';
COMMENT ON COLUMN public.channel.first_sync_start_time IS '資料同步-開始時間';
COMMENT ON COLUMN public.channel.first_sync_end_time IS '資料同步-結束時間';
COMMENT ON COLUMN public.channel.last_sync_time IS '資料同步-程式最後同步時間';
COMMENT ON COLUMN public.channel.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.channel.insert_time IS '建檔時間';
COMMENT ON COLUMN public.channel.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel.modified_time IS '最後修改時間';
--ALTER TABLE public.channel ADD channel_sn varchar(128) NULL;
--COMMENT ON COLUMN public.channel.channel_sn IS '通路編號(通路方)';
--ALTER TABLE public.channel ADD channel_name varchar(256) NULL;
--COMMENT ON COLUMN public.channel.channel_name IS '通路名稱(通路方)';
--ALTER TABLE public.channel ADD multi_spec boolean NOT NULL DEFAULT false;
--COMMENT ON COLUMN public.channel.multi_spec IS '是否為多規商品';

--ALTER TABLE public.channel RENAME COLUMN first_sync_time TO first_sync_start_time;
--ALTER TABLE public.channel ADD first_sync_end_time timestamptz NULL;
--COMMENT ON COLUMN public.channel.first_sync_end_time IS '資料同步-結束時間';
--COMMENT ON COLUMN public.channel.first_sync_start_time IS '資料同步-開始時間';
--ALTER TABLE public.channel ADD last_sync_time timestamptz NULL;
--COMMENT ON COLUMN public.channel.last_sync_time IS '資料同步-程式最後同步時間';

--ALTER TABLE public.channel ADD write_actived boolean NOT NULL DEFAULT false;
--COMMENT ON COLUMN public.channel.write_actived IS '是否啟用通路資料寫入';
--COMMENT ON COLUMN public.channel.enable_sync IS '是否啟用資料同步';

CREATE TABLE public.category
(
    category_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    name character varying(64) NOT NULL,
    status character varying(20) NOT NULL DEFAULT 'enable',
    parent_category_id character varying(4096) NOT NULL,
    level integer NOT NULL,
    insert_account_id character varying(20) NOT NULL,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NOT NULL,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (category_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.category IS '分類';

COMMENT ON COLUMN public.category.name IS '分類名稱';
COMMENT ON COLUMN public.category.status IS '分類狀態';
COMMENT ON COLUMN public.category.parent_category_id IS '父層級分類ID';
COMMENT ON COLUMN public.category.level IS '層級';
COMMENT ON COLUMN public.category.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.category.insert_time IS '建檔時間';
COMMENT ON COLUMN public.category.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.category.modified_time IS '最後修改時間';

CREATE TABLE public.merchant_options
(
    merchant_option_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    name character varying(64) NOT NULL,
    type character varying(20) NOT NULL,
    status character varying(20) NOT NULL DEFAULT 'enable',
    insert_account_id character varying(20) NOT NULL,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NOT NULL,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_option_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.merchant_options IS '公司-自訂選單';

COMMENT ON COLUMN public.merchant_options.name IS '名稱';
COMMENT ON COLUMN public.merchant_options.type IS '選單種類';
COMMENT ON COLUMN public.merchant_options.status IS '選單狀態';
COMMENT ON COLUMN public.merchant_options.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant_options.insert_time IS '建檔時間';
COMMENT ON COLUMN public.merchant_options.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.merchant_options.modified_time IS '最後修改時間';

CREATE TABLE public.warehouse
(
    warehouse_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    warehouse_name character varying(256) NOT NULL,
    status character varying(20) NOT NULL DEFAULT 'enable',
    contact_name character varying(256) NOT NULL,
    contact_phone_number character varying(20) NOT NULL,
    address_zip character varying(10) NOT NULL,
    address_line character varying(512) NOT NULL,
    default_ship boolean NOT NULL DEFAULT false,
    default_return boolean NOT NULL DEFAULT false,
    insert_account_id character varying(20) NOT NULL DEFAULT false,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NOT NULL,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (warehouse_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.warehouse IS '倉庫';

COMMENT ON COLUMN public.warehouse.warehouse_name IS '倉庫名稱';
COMMENT ON COLUMN public.warehouse.status IS '狀態';
COMMENT ON COLUMN public.warehouse.contact_name IS '聯絡人姓名';
COMMENT ON COLUMN public.warehouse.contact_phone_number IS '聯絡人電話';
COMMENT ON COLUMN public.warehouse.address_zip IS '地址_郵遞區碼';
COMMENT ON COLUMN public.warehouse.address_line IS '地址';
COMMENT ON COLUMN public.warehouse.default_ship IS '是否為預設出貨地址';
COMMENT ON COLUMN public.warehouse.default_return IS '是否為預設退貨地址';
COMMENT ON COLUMN public.warehouse.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.warehouse.insert_time IS '建檔時間';
COMMENT ON COLUMN public.warehouse.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.warehouse.modified_time IS '最後修改時間';
CREATE TABLE public.warehouse_mapping_platform
(
    warehouse_mapping_platform_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    warehouse_id character varying(20) NOT NULL,
    channel_id character varying(20) NOT NULL ,
    platform_id character varying(20) NOT NULL,
    platform_setting_id character varying(256) NOT NULL,
    status boolean NOT NULL DEFAULT false,
	address_line character varying(512) NULL DEFAULT '',
    insert_account_id character varying(20) NOT NULL DEFAULT false,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (warehouse_mapping_platform_id),
    CONSTRAINT warehouse_id_fk FOREIGN KEY (warehouse_id)
        REFERENCES public.warehouse (warehouse_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.warehouse_mapping_platform IS '倉庫對應平台資料';

COMMENT ON COLUMN public.warehouse_mapping_platform.warehouse_mapping_platform_id  IS '倉庫名稱';
COMMENT ON COLUMN public.warehouse_mapping_platform.merchant_id IS '公司ID';
COMMENT ON COLUMN public.warehouse_mapping_platform.warehouse_id IS '倉庫ID';
COMMENT ON COLUMN public.warehouse_mapping_platform.channel_id IS '細項平台ID';
COMMENT ON COLUMN public.warehouse_mapping_platform.platform_id IS '平台ID';
COMMENT ON COLUMN public.warehouse_mapping_platform.platform_setting_id IS '對方平台的ID資料';
COMMENT ON COLUMN public.warehouse_mapping_platform.address_line IS '地址';
COMMENT ON COLUMN public.warehouse_mapping_platform.status IS '狀態';
COMMENT ON COLUMN public.warehouse_mapping_platform.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.warehouse_mapping_platform.insert_time IS '建檔時間';
COMMENT ON COLUMN public.warehouse_mapping_platform.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.warehouse_mapping_platform.modified_time IS '最後修改時間';

------------------------------------------------------------------------------------------------------------

CREATE TABLE public.partner
(
    partner_id character varying(20) NOT NULL,
    sname character varying(64),
    name character varying(256),
    logo character varying(512),
    url character varying(512),
    introduce character varying(4096),
    type character varying(20) NOT NULL,
	platform_id character varying(20),
    status character varying(20) NOT NULL DEFAULT 'enable',
    insert_account_id character varying(20) NOT NULL,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NOT NULL,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_id)
);
COMMENT ON TABLE public.partner IS '第3方合作';

COMMENT ON COLUMN public.partner.sname IS '短名稱';
COMMENT ON COLUMN public.partner.name IS '短名稱';
COMMENT ON COLUMN public.partner.logo IS 'Logo Url';
COMMENT ON COLUMN public.partner.url IS '官網 Url';
COMMENT ON COLUMN public.partner.introduce IS '介紹';
COMMENT ON COLUMN public.partner.type IS '合作類型';
COMMENT ON COLUMN public.partner.platform_id IS 'type 為 ec時，需要設定';
COMMENT ON COLUMN public.partner.status IS '狀態';

COMMENT ON COLUMN public.partner.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.partner.insert_time IS '建檔時間';
COMMENT ON COLUMN public.partner.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.partner.modified_time IS '最後修改時間';
INSERT INTO public.partner
(partner_id, sname, "name", logo, url, introduce, "type", status, insert_account_id, insert_time, modified_account_id, modified_time, platform_id)
VALUES('boxful', 'Boxful', 'Boxful', 'https://www.boxful.com.tw/fulfillment/build/imgs/common/logob-f24d8b8386.png', 'https://www.boxful.com.tw/', '', 'warehouse', 'enable', 'cszj6P', '2023-09-06 16:23:36.931', 'cszj6P', '2023-09-06 16:23:36.931', '');
CREATE TABLE public.partner_key
(
    partner_key_id character varying(20) NOT NULL,
	partner_id character varying(20) NOT NULL,
	platform_id character varying(20),
    secret_key character varying(2048),
    secret_iv character varying(2048),
    hash_key character varying(2048),
    allow_ip json,
    webhooks json,
    custom_webhooks json,
    status character varying(20) NOT NULL DEFAULT 'enable',
    insert_account_id character varying(20) NOT NULL,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NOT NULL,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_key_id),
    CONSTRAINT partner_id_fk FOREIGN KEY (partner_id)
        REFERENCES public.partner (partner_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.partner_key IS '第3方合作，金鑰設定';

COMMENT ON COLUMN public.partner_key.platform_id IS '串接platform表';
COMMENT ON COLUMN public.partner_key.secret_key IS 'Secret Key';
COMMENT ON COLUMN public.partner_key.secret_iv IS 'Secret IV';
COMMENT ON COLUMN public.partner_key.hash_key IS 'Hash Key';
COMMENT ON COLUMN public.partner_key.allow_ip IS 'Allow IP';
COMMENT ON COLUMN public.partner_key.webhooks IS 'WebHooks';
COMMENT ON COLUMN public.partner_key.custom_webhooks IS '客戶自訂 WebHooks';
COMMENT ON COLUMN public.partner_key.status IS '狀態';

COMMENT ON COLUMN public.partner_key.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.partner_key.insert_time IS '建檔時間';
COMMENT ON COLUMN public.partner_key.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.partner_key.modified_time IS '最後修改時間';
CREATE TABLE public.merchant_token
(
    merchant_token_id character varying(20) NOT NULL,
	merchant_id character varying(20) NOT NULL,
	partner_id character varying(20) NOT NULL,
    type character varying(20) NOT NULL,
    channel_id character varying(20),
    token character varying(2048),
    webhooks json,
    params jsonb,
    insert_account_id character varying(20) NOT NULL,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NOT NULL,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_token_id),
    CONSTRAINT partner_id_fk FOREIGN KEY (partner_id)
        REFERENCES public.partner (partner_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID,
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.merchant_token IS '第3方合作，Token設定';

COMMENT ON COLUMN public.merchant_token.type IS '合作類型';
COMMENT ON COLUMN public.merchant_token.channel_id IS 'type 為 ec時，需要設定';
COMMENT ON COLUMN public.merchant_token.token IS 'Access Token';
COMMENT ON COLUMN public.merchant_token.webhooks IS 'WebHooks';
COMMENT ON COLUMN public.merchant_token.params IS '自訂參數';

COMMENT ON COLUMN public.merchant_token.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant_token.insert_time IS '建檔時間';
COMMENT ON COLUMN public.merchant_token.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.merchant_token.modified_time IS '最後修改時間';
CREATE TABLE public.merchant_notify
(
    merchant_notify_id character varying(20) NOT NULL,
    create_date date NOT NULL DEFAULT now(),
    merchant_id character varying(20) NOT NULL,
    account_id character varying(20) NOT NULL,
    is_read boolean NOT NULL DEFAULT false,
    level character varying(20) NOT NULL DEFAULT 'info',
    content character varying(4096),
    data json,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_notify_id, create_date)
) PARTITION BY RANGE (create_date);
COMMENT ON TABLE public.merchant_notify IS '公司-通知';
COMMENT ON COLUMN public.merchant_notify.create_date IS 'partition range date';
COMMENT ON COLUMN public.merchant_notify.merchant_id IS '公司的ID';
COMMENT ON COLUMN public.merchant_notify.account_id IS '用戶帳號';
COMMENT ON COLUMN public.merchant_notify.is_read IS '是否已讀取';
COMMENT ON COLUMN public.merchant_notify.level IS '訊息等級';
COMMENT ON COLUMN public.merchant_notify.content IS '訊息內容';
COMMENT ON COLUMN public.merchant_notify.data IS '互動訊息內容';
COMMENT ON COLUMN public.merchant_notify.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant_notify.insert_time IS '建檔時間';

CREATE INDEX merchant_notify_idx ON public.merchant_notify USING btree
    (merchant_id ASC NULLS LAST, account_id ASC NULLS LAST, is_read ASC NULLS LAST);
	
CREATE TABLE public.merchant_notify_default PARTITION OF public.merchant_notify DEFAULT;

	
CREATE TABLE public.merchant_notify_read
(
    merchant_notify_read_id character varying(20) NOT NULL,
	merchant_id character varying(20) NOT NULL,
	account_id character varying(20) NOT NULL,
    last_read_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_notify_read_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.merchant_notify_read IS '公司-通知-最後讀取時間';
COMMENT ON COLUMN public.merchant_notify_read.merchant_id IS '公司的ID';
COMMENT ON COLUMN public.merchant_notify_read.account_id IS '用戶帳號';
COMMENT ON COLUMN public.merchant_notify_read.last_read_time IS '最後讀取時間';

CREATE INDEX merchant_notify_read_idx ON public.merchant_notify_read USING btree
    (merchant_id ASC NULLS LAST, account_id ASC NULLS LAST);

CREATE INDEX merchant_notify_insert_time_idx ON public.merchant_notify using brin(insert_time);

----------------------------------------------------------------------------------------------------------

CREATE TABLE public.orders_statistic
	(
		order_statistic_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		sku_total_qty int NOT NULL DEFAULT 0,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (order_statistic_id,count_date),
		CONSTRAINT order_statistic_un UNIQUE (merchant_id, platform_id, count_date, user_local_offset)

	
	)PARTITION BY RANGE (count_date);
CREATE TABLE orders_statistic_default PARTITION OF orders_statistic DEFAULT;

--ALTER TABLE orders_statistic  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
--ALTER TABLE orders_statistic  ADD COLUMN sku_total_qty  int DEFAULT 0 NOT NULL ;

COMMENT ON COLUMN public.orders_statistic.user_local_offset IS '使用者設定時區的offset';
 

CREATE TABLE public.orders_statistic_detail
	(
		order_statistic_detail_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		channel_id character varying (20) NOT NULL,
		sell_pack_id character varying (20) NOT NULL,
		product_id character varying (20) NOT NULL,
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		item_number character varying (512) NULL,
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (order_statistic_detail_id,count_date),
		CONSTRAINT order_statistic_detail_un UNIQUE (merchant_id, platform_id, channel_id, count_date, user_local_offset, item_number, sell_pack_id, product_id)

	
	)PARTITION BY RANGE (count_date);	
CREATE TABLE orders_statistic_detail_default PARTITION OF orders_statistic_detail DEFAULT;

--ALTER TABLE orders_statistic_detail  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.orders_statistic_detail.user_local_offset IS '使用者設定時區的offset';

  
CREATE TABLE public.canceled_orders_statistic
	(
		canceled_order_statistic_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		sku_total_qty int NOT NULL DEFAULT 0,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (canceled_order_statistic_id,count_date),
		CONSTRAINT canceled_order_statistic_un UNIQUE (merchant_id, platform_id, count_date, user_local_offset)

	
	)PARTITION BY RANGE (count_date);
CREATE TABLE canceled_orders_statistic_default PARTITION OF canceled_orders_statistic DEFAULT;

--ALTER TABLE canceled_orders_statistic  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
--ALTER TABLE canceled_orders_statistic  ADD COLUMN sku_total_qty  int DEFAULT 0 NOT NULL ;
COMMENT ON COLUMN public.canceled_orders_statistic.user_local_offset IS '使用者設定時區的offset';
 
CREATE TABLE public.canceled_orders_statistic_detail
	(
		canceled_order_statistic_detail_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		channel_id character varying (20) NOT NULL,
		sell_pack_id character varying (20) NOT NULL,
		product_id character varying (20) NOT NULL,
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		item_number character varying (512) NULL,
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (canceled_order_statistic_detail_id,count_date),
		CONSTRAINT canceled_order_statistic_detail_un UNIQUE (merchant_id, platform_id, channel_id, count_date, user_local_offset, item_number, sell_pack_id, product_id)

	
	)PARTITION BY RANGE (count_date);
CREATE TABLE canceled_orders_statistic_detail_default PARTITION OF canceled_orders_statistic_detail DEFAULT;

--ALTER TABLE canceled_orders_statistic_detail  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.canceled_orders_statistic_detail.user_local_offset IS '使用者設定時區的offset';
CREATE TABLE public.refund_orders_statistic
	(
		refund_order_statistic_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		sku_total_qty int NOT NULL DEFAULT 0,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (refund_order_statistic_id,count_date),
		CONSTRAINT refund_order_statistic_un UNIQUE (merchant_id, platform_id, count_date, user_local_offset)

	
	)PARTITION BY RANGE (count_date);
CREATE TABLE refund_orders_statistic_default PARTITION OF refund_orders_statistic DEFAULT;

--ALTER TABLE refund_orders_statistic  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
--ALTER TABLE refund_orders_statistic  ADD COLUMN sku_total_qty  int DEFAULT 0 NOT NULL ;
COMMENT ON COLUMN public.refund_orders_statistic.user_local_offset IS '使用者設定時區的offset';
CREATE TABLE public.refund_orders_statistic_detail
	(
		refund_order_statistic_detail_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		channel_id character varying (20) NOT NULL,
		sell_pack_id character varying (20) NOT NULL,
		product_id character varying (20) NOT NULL,
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		item_number character varying (512) NULL,
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (refund_order_statistic_detail_id,count_date),
		CONSTRAINT refund_order_statistic_detail_un UNIQUE (merchant_id, platform_id, channel_id, count_date, user_local_offset, item_number, sell_pack_id, product_id)

	
	) PARTITION BY RANGE (count_date);  
CREATE TABLE refund_orders_statistic_detail_default PARTITION OF refund_orders_statistic_detail DEFAULT;

--ALTER TABLE refund_orders_statistic_detail  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.refund_orders_statistic_detail.user_local_offset IS '使用者設定時區的offset';
CREATE TABLE public.qty_daily_energy_statistic_records
	(
		qty_daily_energy_statistic_record_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		channel_id character varying (20) NOT NULL,
		sell_pack_id character varying (20) NOT NULL,
		product_id character varying (20) NOT NULL,
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		item_number character varying (512)  NULL,
		three_days_qty int  NULL,
		seven_days_qty int  NULL,
		thirty_days_qty int  NULL,
		sixty_days_qty int  NULL,
		insert_account_id character varying (20) NOT NULL,
		insert_time timestamp with time zone NOT NULL,
		PRIMARY KEY (qty_daily_energy_statistic_record_id,count_date),
		CONSTRAINT qty_daily_energy_statistic_records_un 
		UNIQUE (merchant_id, platform_id, channel_id, count_date, user_local_offset, item_number, sell_pack_id, product_id)

	
	) PARTITION BY RANGE (count_date);
CREATE TABLE qty_daily_energy_statistic_records_default PARTITION OF qty_daily_energy_statistic_records DEFAULT;

--ALTER TABLE qty_daily_energy_statistic_records  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.qty_daily_energy_statistic_records.user_local_offset IS '使用者設定時區的offset';
CREATE TABLE public.queue_sensitive_error_records
(
    queue_sensitive_error_records_id character varying(20) NOT NULL,
	queue_topic character varying(256) NOT NULL,
	queue_key character varying(256)  NULL,
    queue_value character varying(4096) NOT NULL,
    queue_offset int,
    queue_timestamp timestamp,
    queue_timestampType character varying(256) NOT NULL,
    insert_account_id character varying(20) NOT NULL,
    insert_date timestamp with time zone NOT NULL,
    modified_account_id character varying(20) ,
    modified_date timestamp with time zone,
	
    PRIMARY KEY (queue_sensitive_error_records_id)
	 
);

COMMENT ON COLUMN public.queue_sensitive_error_records.queue_sensitive_error_records_id IS 'id';
COMMENT ON COLUMN public.queue_sensitive_error_records.queue_topic IS 'Queue主題';
COMMENT ON COLUMN public.queue_sensitive_error_records.queue_key IS 'partition 主key';
COMMENT ON COLUMN public.queue_sensitive_error_records.queue_value IS '內容';
COMMENT ON COLUMN public.queue_sensitive_error_records.queue_offset IS '順序';
COMMENT ON COLUMN public.queue_sensitive_error_records.queue_timestamp IS '時間';
COMMENT ON COLUMN public.queue_sensitive_error_records.queue_timestampType IS '時間類別';
COMMENT ON COLUMN public.queue_sensitive_error_records.insert_account_id IS '建檔者';
COMMENT ON COLUMN public.queue_sensitive_error_records.insert_date IS '建檔時間';
COMMENT ON COLUMN public.queue_sensitive_error_records.modified_account_id IS '修改者';
COMMENT ON COLUMN public.queue_sensitive_error_records.modified_date IS '修改時間';
CREATE TABLE public.sell_pack_recommend_quantities
(
    sell_pack_recommend_quantiy_id character varying(20) NOT NULL,
merchant_id character varying(20) NOT NULL,
platform_id character varying(20) NOT NULL,
channel_id character varying(20) NOT NULL,
product_id character varying(20) NOT NULL,
sell_pack_id character varying(20) NOT NULL,
item_number character varying(512) NOT NULL,
recommend_qty int  null,
three_days_qty  json  null,
seven_days_qty json  null,
thirty_days_qty json  null,
sixty_days_qty json  null,
record_date    date ,
last_modified_dt  timestamp with time zone,
last_confirm_dt timestamp with time zone,
    PRIMARY KEY (sell_pack_recommend_quantiy_id)
	 
);

CREATE INDEX sell_pack_recommend_quantities_product_id_idx ON public.sell_pack_recommend_quantities USING btree
    (merchant_id ASC NULLS LAST, product_id ASC NULLS LAST);

COMMENT ON TABLE public.sell_pack_recommend_quantities IS '推薦表';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.sell_pack_recommend_quantiy_id IS 'ID';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.merchant_id IS '串接merchant表';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.platform_id IS '串接 platform 表';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.channel_id IS '串接 channel 表';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.product_id IS '串接 product 表';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.sell_pack_id IS '串接 sell_pack 表';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.item_number IS '料號';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.recommend_qty IS '分配量';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.three_days_qty IS '近三日量';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.seven_days_qty IS '近七日量';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.thirty_days_qty IS '近三十日量';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.sixty_days_qty IS '近六十日量';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.record_date IS '量體資料日期';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.last_modified_dt IS '最後修改的時間';
COMMENT ON COLUMN public.sell_pack_recommend_quantities.last_confirm_dt IS '最後採用的時間';

	
ALTER TABLE sell_pack_recommend_quantities ADD CONSTRAINT sell_pack_recommend_quantities_un    
UNIQUE (merchant_id, platform_id, channel_id, sell_pack_id);

----------------------------------------------------------------------------

--	ALTER TABLE canceled_orders_statistic  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.canceled_orders_statistic.user_local_offset IS '使用者設定時區的offset';

--	ALTER TABLE canceled_orders_statistic_detail  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.canceled_orders_statistic_detail.user_local_offset IS '使用者設定時區的offset';

--	ALTER TABLE orders_statistic  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.orders_statistic.user_local_offset IS '使用者設定時區的offset';

--	ALTER TABLE orders_statistic_detail  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.orders_statistic_detail.user_local_offset IS '使用者設定時區的offset';

--	ALTER TABLE refund_orders_statistic  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.refund_orders_statistic.user_local_offset IS '使用者設定時區的offset';

--	ALTER TABLE refund_orders_statistic_detail  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.refund_orders_statistic_detail.user_local_offset IS '使用者設定時區的offset';

--	ALTER TABLE qty_daily_energy_statistic_records  ADD COLUMN user_local_offset VARCHAR(50)DEFAULT '+00:00' ;
COMMENT ON COLUMN public.qty_daily_energy_statistic_records.user_local_offset IS '使用者設定時區的offset';

---- 增加統計的sku量

--	ALTER TABLE orders_statistic  ADD COLUMN sku_total_qty  int DEFAULT 0 NOT NULL ;
--	ALTER TABLE canceled_orders_statistic  ADD COLUMN sku_total_qty  int DEFAULT 0 NOT NULL ;
--	ALTER TABLE refund_orders_statistic  ADD COLUMN sku_total_qty  int DEFAULT 0 NOT NULL ;
---- 修改unique 條件
ALTER TABLE orders_statistic_detail DROP CONSTRAINT order_statistic_detail_un    ;
ALTER TABLE orders_statistic_detail ADD CONSTRAINT order_statistic_detail_un      
UNIQUE (merchant_id, platform_id, channel_id, count_date, item_number, sell_pack_id, product_id,user_local_offset);

ALTER TABLE orders_statistic DROP CONSTRAINT order_statistic_un     ;
ALTER TABLE orders_statistic ADD CONSTRAINT order_statistic_un       
UNIQUE (merchant_id, platform_id, count_date,user_local_offset);

ALTER TABLE canceled_orders_statistic_detail DROP CONSTRAINT canceled_order_statistic_detail_un  ;
ALTER TABLE canceled_orders_statistic_detail ADD CONSTRAINT canceled_order_statistic_detail_un    
UNIQUE (merchant_id, platform_id, channel_id, count_date, item_number, sell_pack_id, product_id,user_local_offset);

ALTER TABLE canceled_orders_statistic DROP CONSTRAINT canceled_order_statistic_un   ;
ALTER TABLE canceled_orders_statistic ADD CONSTRAINT canceled_order_statistic_un     
UNIQUE (merchant_id, platform_id, count_date,user_local_offset);

ALTER TABLE refund_orders_statistic_detail DROP CONSTRAINT refund_order_statistic_detail_un;
ALTER TABLE refund_orders_statistic_detail ADD CONSTRAINT refund_order_statistic_detail_un  
UNIQUE (merchant_id, platform_id, channel_id, count_date, item_number, sell_pack_id, product_id,user_local_offset);
ALTER TABLE refund_orders_statistic DROP CONSTRAINT refund_order_statistic_un ;
ALTER TABLE refund_orders_statistic ADD CONSTRAINT refund_order_statistic_un   
UNIQUE (merchant_id, platform_id, count_date,user_local_offset);

ALTER TABLE qty_daily_energy_statistic_records DROP CONSTRAINT qty_daily_energy_statistic_records_un      ;
ALTER TABLE qty_daily_energy_statistic_records ADD CONSTRAINT qty_daily_energy_statistic_records_un        
UNIQUE (merchant_id, platform_id, channel_id, count_date, item_number, sell_pack_id, product_id,user_local_offset);
	

CREATE TABLE public.safety_qty_alert_records
	(
		safety_qty_alert_record_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		count_date date NOT NULL,
		product_id character varying (500) NOT NULL,
		item_number character varying (512) NOT NULL,
		product_name character varying (500)  NULL,
		sellpacks_amount int NOT NULL,
		safety_qty int  NULL,
		qty int NOT NULL,
		on_the_way_qty int  NULL,
		can_be_sell_qty int NOT NULL,
		predict_future_need_qty int  NULL,
		can_be_sell_after_future_qty int NOT NULL,
		insert_account_id character varying (20) NOT NULL,
		insert_time timestamp with time zone NOT NULL,
		modified_account_id character varying (20) NOT NULL,
		modified_time timestamp with time zone NOT NULL,
		PRIMARY KEY (safety_qty_alert_record_id,count_date),
		CONSTRAINT safety_qty_alert_record_id_un 
		UNIQUE (merchant_id, count_date,product_id,item_number)
	
	) PARTITION BY RANGE (count_date);
CREATE TABLE safety_qty_alert_records_default PARTITION OF safety_qty_alert_records DEFAULT;

CREATE TABLE public.refund_orders_by_channel_statistic
	(
		refund_order_by_channel_statistic_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		channel_id character varying (20) NOT NULL,
        channel_name character varying(256),
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		sku_total_qty int NOT NULL DEFAULT 0,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (refund_order_by_channel_statistic_id,count_date),
		CONSTRAINT refund_orders_by_channel_statistic_un UNIQUE (merchant_id, platform_id,channel_id, count_date, user_local_offset)

	
	)PARTITION BY RANGE (count_date);
CREATE TABLE refund_orders_by_channel_statistic_default PARTITION OF refund_orders_by_channel_statistic DEFAULT;
 
CREATE TABLE public.canceled_orders_by_channel_statistic
	(
		canceled_order_by_channel_statistic_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		channel_id character varying (20) NOT NULL,
        channel_name character varying(256),
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		sku_total_qty int NOT NULL DEFAULT 0,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (canceled_order_by_channel_statistic_id,count_date),
		CONSTRAINT canceled_orders_by_channel_statistic_un UNIQUE (merchant_id, platform_id,channel_id, count_date, user_local_offset)

	
	)PARTITION BY RANGE (count_date);
CREATE TABLE canceled_orders_by_channel_statistic_default PARTITION OF canceled_orders_by_channel_statistic DEFAULT;
 
 CREATE TABLE public.orders_by_channel_statistic
	(
		order_by_channel_statistic_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id nchar varying (50) NOT NULL,
		channel_id character varying (20) NOT NULL,
        channel_name character varying(256),
		count_date date NOT NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		total_qty int NOT NULL,
		total_amount decimal(32,2) NOT NULL,
		sku_total_qty int NOT NULL DEFAULT 0,
		currency character varying (3) NOT NULL,
		modified_dt timestamp with time zone NOT NULL,
		last_order_dt timestamp with time zone NOT NULL,
		PRIMARY KEY (order_by_channel_statistic_id,count_date),
		CONSTRAINT orders_by_channel_statistic_un UNIQUE (merchant_id, platform_id,channel_id, count_date, user_local_offset)

	
	)PARTITION BY RANGE (count_date);
CREATE TABLE orders_by_channel_statistic_default PARTITION OF orders_by_channel_statistic DEFAULT;

  
CREATE TABLE public.file_upload_records
(
    file_upload_record_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    file_name character varying(256) NOT NULL,
	platform_id character varying(20) NULL,
	channel_id character varying(20)  NULL,
	json_type character varying(50)  NULL,
    working_process_percentage decimal(5,2) NOT NULL ,
    failed_rank_count int NULL,
    success_rank_count int  NULL,
    total_rank_count int NULL  ,
    status int  NULL DEFAULT 0,
	notify_account_id  json NOT NULL ,
    insert_account_id character varying(20) NOT NULL DEFAULT false,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (file_upload_record_id)
    
);
COMMENT ON TABLE public.file_upload_records IS '檔案上傳紀錄';

COMMENT ON COLUMN public.file_upload_records.file_upload_record_id  IS 'ID';
COMMENT ON COLUMN public.file_upload_records.merchant_id IS '公司ID';
COMMENT ON COLUMN public.file_upload_records.file_name IS '檔名';
COMMENT ON COLUMN public.file_upload_records.working_process_percentage IS '進度';
COMMENT ON COLUMN public.file_upload_records.failed_rank_count IS '失敗筆數';
COMMENT ON COLUMN public.file_upload_records.success_rank_count IS '成功筆數';
COMMENT ON COLUMN public.file_upload_records.total_rank_count IS '總筆數';
COMMENT ON COLUMN public.file_upload_records.status IS '狀態';
COMMENT ON COLUMN public.file_upload_records.notify_account_id IS '通知的人員編號';
COMMENT ON COLUMN public.file_upload_records.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.file_upload_records.insert_time IS '建檔時間';
COMMENT ON COLUMN public.file_upload_records.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.file_upload_records.modified_time IS '最後修改時間';

CREATE TABLE public.activities
(
    activity_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    channel_ids jsonb NOT NULL,
    activity_name character varying(256) NOT NULL,
    activity_code character varying(10) NOT NULL,
    activity_start_time timestamp with time zone NOT  NULL,
	activity_end_time timestamp with time zone NOT  NULL,
    activity_type int  NOT NULL  ,
    amount_maximum decimal(32,5)  NULL ,
	amount_minimum  decimal(32,5)   NULL ,
	product_infos jsonb  NULL,
	order_tags jsonb NULL,
	giveaway_content json  not NULL ,
	random_pick_one boolean  not NULL DEFAULT false ,
	activity_status character varying(20) not NULL DEFAULT 'disable',
    insert_account_id character varying(20) NOT NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id)
    
);
COMMENT ON TABLE public.activities IS '活動贈品';

COMMENT ON COLUMN public.activities.activity_id  IS 'ID';
COMMENT ON COLUMN public.activities.merchant_id IS '公司ID';
COMMENT ON COLUMN public.activities.channel_ids IS '通路ID';
COMMENT ON COLUMN public.activities.activity_name IS '活動名稱';
COMMENT ON COLUMN public.activities.activity_code IS '活動代號';
COMMENT ON COLUMN public.activities.activity_start_time IS '活動開始時間';
COMMENT ON COLUMN public.activities.activity_end_time IS '活動結束時間';
COMMENT ON COLUMN public.activities.activity_type IS '活動條件類型';
COMMENT ON COLUMN public.activities.amount_maximum IS '金額上限';
COMMENT ON COLUMN public.activities.amount_minimum IS '金額下限';
COMMENT ON COLUMN public.activities.product_infos IS '商品貨號';
COMMENT ON COLUMN public.activities.giveaway_content IS '贈品內容';
COMMENT ON COLUMN public.activities.random_pick_one IS '是否隨機取一項';
COMMENT ON COLUMN public.activities.activity_status IS '活動狀態';
COMMENT ON COLUMN public.activities.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.activities.insert_time IS '建檔時間';
COMMENT ON COLUMN public.activities.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.activities.modified_time IS '最後修改時間';
 
 CREATE TABLE public.channel_alive_logs
	(
		channel_alive_log_id character varying (20) NOT NULL,
		channel_id character varying (20) NULL,
		platform_id character varying (50) NOT NULL,
		status_code int NOT NULL,
		http_status_code int NULL,
		work_type int NOT NULL ,
		execute_time_interval int  NOT NULL,
		insert_account_id character varying (20) NOT NULL,
		insert_time timestamp with time zone NOT NULL,
		PRIMARY KEY (channel_alive_log_id,insert_time)
	
	)PARTITION BY RANGE (insert_time);
CREATE TABLE channel_alive_logs_default PARTITION OF channel_alive_logs DEFAULT;
COMMENT ON TABLE public.channel_alive_logs IS '通路檢查紀錄';
COMMENT ON COLUMN public.channel_alive_logs.channel_alive_log_id IS 'channel ID';
COMMENT ON COLUMN public.channel_alive_logs.platform_id IS '通路ID';
COMMENT ON COLUMN public.channel_alive_logs.status_code IS '是否成功';
COMMENT ON COLUMN public.channel_alive_logs.http_status_code IS 'http status code';
COMMENT ON COLUMN public.channel_alive_logs.work_type IS '是哪種log';
COMMENT ON COLUMN public.channel_alive_logs.execute_time_interval IS '所花時間';
COMMENT ON COLUMN public.channel_alive_logs.insert_account_id IS '紀錄者';
COMMENT ON COLUMN public.channel_alive_logs.insert_time IS '紀錄時間';

 
 

 
 CREATE TABLE public.sku_qty_daily_statistic
	(
		sku_qty_daily_statistic_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		platform_id character varying (20) NOT NULL,
		channel_id character varying (20) NOT NULL,
		product_id character varying (20) NOT NULL,
		item_number character varying (512)  NULL,
		count_date date NOT NULL,
		sku_total_qty int NULL,
		cancel_total_qty int NULL,
		refund_total_qty int NULL,
		user_local_offset character varying (50) NOT NULL DEFAULT '+00:00',
		last_order_dt timestamp with time zone NOT NULL,
		update_time timestamp with time zone NOT NULL,
		
		PRIMARY KEY (sku_qty_daily_statistic_id,count_date),
		CONSTRAINT sku_qty_daily_statistic_un UNIQUE (merchant_id, platform_id, channel_id,product_id ,item_number,count_date, user_local_offset  )

	)PARTITION BY RANGE (count_date);
CREATE TABLE sku_qty_daily_statistic_default PARTITION OF sku_qty_daily_statistic DEFAULT;
COMMENT ON TABLE public.sku_qty_daily_statistic IS '每日SKU銷量統計';
COMMENT ON COLUMN public.sku_qty_daily_statistic.sku_qty_daily_statistic_id IS ' ID';
COMMENT ON COLUMN public.sku_qty_daily_statistic.merchant_id IS 'merchant編號';
COMMENT ON COLUMN public.sku_qty_daily_statistic.platform_id IS '通路名稱';
COMMENT ON COLUMN public.sku_qty_daily_statistic.channel_id IS 'channel編號';
COMMENT ON COLUMN public.sku_qty_daily_statistic.product_id IS 'sku ID';
COMMENT ON COLUMN public.sku_qty_daily_statistic.item_number IS '商品貨號';
COMMENT ON COLUMN public.sku_qty_daily_statistic.count_date IS '日期';
COMMENT ON COLUMN public.sku_qty_daily_statistic.sku_total_qty IS '總商品量';
COMMENT ON COLUMN public.sku_qty_daily_statistic.cancel_total_qty IS '總取消量';

COMMENT ON COLUMN public.sku_qty_daily_statistic.refund_total_qty IS '總退貨量';
COMMENT ON COLUMN public.sku_qty_daily_statistic.user_local_offset IS '時區';
COMMENT ON COLUMN public.sku_qty_daily_statistic.last_order_dt IS '最新的訂單日期';
COMMENT ON COLUMN public.sku_qty_daily_statistic.update_time IS '資料更新時間';

CREATE TABLE public.combination_products
(
    combination_product_id character varying(20) NOT NULL,
	merchant_id character varying (20)  NOT NULL,
	item_number character varying (512) NOT NULL,
	sub_product_ids jsonb NOT NULL,
    insert_account_id character varying(20) NOT NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (combination_product_id)
    
);
COMMENT ON TABLE public.combination_products IS '組合商品MAPID';

COMMENT ON COLUMN public.combination_products.combination_product_id IS '組合ID';
COMMENT ON COLUMN public.combination_products.merchant_id IS 'merchantID';
COMMENT ON COLUMN public.combination_products.item_number IS '料號';
COMMENT ON COLUMN public.combination_products.sub_product_ids IS '子商品的IDs';
COMMENT ON COLUMN public.combination_products.insert_account_id IS '新增人員ID';
COMMENT ON COLUMN public.combination_products.insert_time IS '新增時間';
COMMENT ON COLUMN public.combination_products.modified_account_id IS '修改人員ID';
COMMENT ON COLUMN public.combination_products.modified_time IS '修改時間';

CREATE TABLE public.exclude_setting_products
(
    exclude_setting_product_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    item_number character varying(512) NOT NULL,
    exclude_start_time timestamp with time zone   NULL ,
	exclude_end_time timestamp with time zone   NULL  ,
    status character varying(256) NOT NULL DEFAULT 'enable',
    insert_account_id character varying(20) NOT NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (exclude_setting_product_id)
    
);
COMMENT ON TABLE public.exclude_setting_products IS '排除商品設定';

COMMENT ON COLUMN public.exclude_setting_products.exclude_setting_product_id  IS '排除ID';
COMMENT ON COLUMN public.exclude_setting_products.merchant_id IS 'merchant編號';
COMMENT ON COLUMN public.exclude_setting_products.item_number IS '料號';
COMMENT ON COLUMN public.exclude_setting_products.exclude_start_time IS '排除區間開始';
COMMENT ON COLUMN public.exclude_setting_products.exclude_end_time IS '排除區間結束';
COMMENT ON COLUMN public.exclude_setting_products.status IS '狀態';
COMMENT ON COLUMN public.exclude_setting_products.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.exclude_setting_products.insert_time IS '建檔時間';
COMMENT ON COLUMN public.exclude_setting_products.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.exclude_setting_products.modified_time IS '最後修改時間';
CREATE TABLE public.merchant_config
(
    merchant_id character varying(20) NOT NULL,
    system_options jsonb NOT NULL,
    vip_extra_options jsonb NOT NULL,
    discount_points integer NOT NULL DEFAULT 0,
    note varchar(1024) NULL,
    responsible_account_id varchar(20) NULL,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20),
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);
COMMENT ON TABLE public.merchant_config IS '公司額外設定';

COMMENT ON COLUMN public.merchant_config.system_options IS '系統控制選項';
COMMENT ON COLUMN public.merchant_config.vip_extra_options IS 'VIP 額外購買選項';
COMMENT ON COLUMN public.merchant_config.discount_points IS '優惠點數';
COMMENT ON COLUMN public.merchant_config.note IS '備註';
COMMENT ON COLUMN public.merchant_config.responsible_account_id IS 'OneEC業務服務人員';
COMMENT ON COLUMN public.merchant_config.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant_config.insert_time IS '建檔時間';
COMMENT ON COLUMN public.merchant_config.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.merchant_config.modified_time IS '最後修改時間';

CREATE TABLE IF NOT EXISTS public.delivery_company_mapping_platforms
(
    delivery_company_mapping_platform_id character varying(20) NOT NULL,
    merchant_id character varying(20) NOT NULL,
    platform_id character varying(20)  NOT NULL,
    channel_id character varying(20)  NOT NULL,
    channel_delivery_method_id character varying(100) ,
    channel_delivery_method_name character varying(200)  NOT NULL,
    channel_delivery_method_info character varying(2000) ,
    channel_status character varying(20) ,
    status character varying(20)  DEFAULT 'enable'::character varying,
    shipcode_company character varying(5)  NULL,
    insert_account_id character varying(20) ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT delivery_company_mapping_platforms_pkey PRIMARY KEY (delivery_company_mapping_platform_id)
)

TABLESPACE pg_default;

ALTER TABLE public.delivery_company_mapping_platforms
    OWNER to oneec;

COMMENT ON TABLE public.delivery_company_mapping_platforms
    IS '設定檔';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.delivery_company_mapping_platform_id
    IS '對外ID';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.merchant_id
    IS '公司ID';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.platform_id
    IS '通路ID';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.channel_id
    IS 'channelID';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.channel_delivery_method_id
    IS '平台ID';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.channel_delivery_method_name
    IS '平台送貨名稱';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.channel_delivery_method_info
    IS '平台送貨簡介';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.channel_status
    IS '平台狀態';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.status
    IS '狀態';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.shipcode_company
    IS '我方公司代號';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.insert_account_id
    IS '建檔人員編號';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.insert_time
    IS '建檔時間';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.modified_account_id
    IS '修改人員編號';

COMMENT ON COLUMN public.delivery_company_mapping_platforms.modified_time
    IS '最後修改時間';
	
	
	


 CREATE TABLE public.platform_action_logs
	(
		platform_action_log_id character varying (20) NOT NULL,
		merchant_id character varying (20) NOT NULL,
		log_date date NOT NULL,
		log_type character varying (20) NOT NULL,
		log_level character varying (20)  NULL,
		info jsonb  NULL,
		record_id character varying (20) NOT NULL,
		record_id_type character varying (20) NOT NULL,
		record_status character varying (20)  NULL,
		record_platform character varying (20)  NULL,
		trigger_starter character varying (100)  NULL,
		insert_account_id character varying (100)  NULL,
		insert_time timestamp  with time zone NOT NULL default now(),
		modified_account_id character varying (100)  NULL,
		modified_time timestamp  with time zone  NULL,
		
		PRIMARY KEY (platform_action_log_id,log_date)

	)PARTITION BY RANGE (log_date);
CREATE TABLE platform_action_logs_default PARTITION OF platform_action_logs DEFAULT;
COMMENT ON TABLE public.platform_action_logs IS '每日SKU銷量統計';
COMMENT ON COLUMN public.platform_action_logs.platform_action_log_id    IS 'ID';
COMMENT ON COLUMN public.platform_action_logs.merchant_id    IS '主表ID';
COMMENT ON COLUMN public.platform_action_logs.log_date    IS '紀錄的日期';
COMMENT ON COLUMN public.platform_action_logs.log_type    IS '打哪種的動作Log';
COMMENT ON COLUMN public.platform_action_logs.log_level    IS 'log等級';
COMMENT ON COLUMN public.platform_action_logs.info    IS '主資料';
COMMENT ON COLUMN public.platform_action_logs.record_id    IS '主要紀錄的資訊ID';
COMMENT ON COLUMN public.platform_action_logs.record_id_type    IS '主要紀錄的資訊ID的型態';
COMMENT ON COLUMN public.platform_action_logs.record_status    IS '此API成功與否';
COMMENT ON COLUMN public.platform_action_logs.record_platform    IS '打向哪個平台';
COMMENT ON COLUMN public.platform_action_logs.trigger_starter    IS '觸發者';
COMMENT ON COLUMN public.platform_action_logs.insert_account_id    IS '建檔人員編號';
COMMENT ON COLUMN public.platform_action_logs.insert_time    IS '建檔時間';
COMMENT ON COLUMN public.platform_action_logs.modified_account_id    IS '修改人員編號';
COMMENT ON COLUMN public.platform_action_logs.modified_time    IS '最後修改時間';

CREATE TABLE public.channel_categorys
(
    channel_category_id character varying(20) NOT NULL,
    channel_client_category_id character varying(100) NOT NULL,
    channel_client_category_name character varying(100) NOT NULL,
    channel_client_category_level character varying(100)  NULL,
    channel_client_category_parent_id character varying(100)  NULL,
    status character varying(10) NOT NULL,
    platform character varying(20) NOT NULL,
    merchant_id character varying(20)  NULL,
    other_data jsonb  NULL,
    
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_category_id)
    
);

COMMENT ON TABLE public.channel_categorys IS '分類樹';

COMMENT ON COLUMN public.channel_categorys.channel_category_id  IS 'ID';
COMMENT ON COLUMN public.channel_categorys.channel_client_category_id IS '前台分類ID';
COMMENT ON COLUMN public.channel_categorys.channel_client_category_name IS '前台分類 名稱';
COMMENT ON COLUMN public.channel_categorys.channel_client_category_level IS '階層';
COMMENT ON COLUMN public.channel_categorys.channel_client_category_parent_id IS '父親ID ';
COMMENT ON COLUMN public.channel_categorys.status IS '是否啟用';
COMMENT ON COLUMN public.channel_categorys.platform IS '平台';
COMMENT ON COLUMN public.channel_categorys.merchant_id IS '如個人樹的才有';
COMMENT ON COLUMN public.channel_categorys.other_data IS '其它不重要的';
COMMENT ON COLUMN public.channel_categorys.insert_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_categorys.insert_time IS '最後修改時間';
COMMENT ON COLUMN public.channel_categorys.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_categorys.modified_time IS '最後修改時間';
CREATE TABLE public.channel_specs
(
    channel_spec_id character varying(20) NOT NULL,
    channel_client_category_id character varying(100)  NULL,
    channel_client_spec_id character varying(100)  NULL,
    channel_client_spec_name character varying(100) NOT NULL,
    channel_client_spec_display_name jsonb  NULL,
    channel_client_spec_chooice_type character varying(20)  NULL,
    channel_client_spec_option jsonb  NULL,
    status character varying(10) NOT NULL,
    platform character varying(20) NOT NULL,
	 merchant_id character varying(20)  NULL,
    other_data jsonb  NULL,
    
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_spec_id)
    
);

COMMENT ON TABLE public.channel_specs IS '規格選項';

COMMENT ON COLUMN public.channel_specs.channel_spec_id  IS 'ID';
COMMENT ON COLUMN public.channel_specs.channel_client_category_id IS '分類ID';
COMMENT ON COLUMN public.channel_specs.channel_client_spec_id IS '規格ID';
COMMENT ON COLUMN public.channel_specs.channel_client_spec_name IS '規格名稱';
COMMENT ON COLUMN public.channel_specs.channel_client_spec_display_name IS '規格名稱';
COMMENT ON COLUMN public.channel_specs.channel_client_spec_chooice_type IS '選項種類 ';
COMMENT ON COLUMN public.channel_specs.channel_client_spec_option IS '選項 ';
COMMENT ON COLUMN public.channel_specs.status IS '是否啟用';
COMMENT ON COLUMN public.channel_specs.platform IS '平台';
COMMENT ON COLUMN public.channel_specs.merchant_id IS 'merchant ID';
COMMENT ON COLUMN public.channel_specs.other_data IS '其它不重要的';
COMMENT ON COLUMN public.channel_specs.insert_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_specs.insert_time IS '最後修改時間';
COMMENT ON COLUMN public.channel_specs.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_specs.modified_time IS '最後修改時間';
CREATE TABLE public.channel_category_spec_relations
(
    channel_category_spec_relation_id character varying(20) NOT NULL,
    channel_category_id character varying(20)  NULL,
    channel_spec_id character varying(20) NOT NULL,
    channel_form_spec_id character varying(100)  NULL,
    channel_form_spec_name character varying(100)  NULL,
    is_option boolean  DEFAULT true,
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_category_spec_relation_id)
    
);

COMMENT ON TABLE public.channel_category_spec_relations IS '分類-規格關聯';

COMMENT ON COLUMN public.channel_category_spec_relations.channel_category_spec_relation_id  IS 'ID';
COMMENT ON COLUMN public.channel_category_spec_relations.channel_category_id IS '規格ID';
COMMENT ON COLUMN public.channel_category_spec_relations.channel_spec_id IS '規格名稱';
COMMENT ON COLUMN public.channel_category_spec_relations.channel_form_spec_id IS '規格名稱';
COMMENT ON COLUMN public.channel_category_spec_relations.channel_form_spec_name IS '選項種類 ';
COMMENT ON COLUMN public.channel_category_spec_relations.is_option IS '是否必填 ';
COMMENT ON COLUMN public.channel_category_spec_relations.insert_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_category_spec_relations.insert_time IS '最後修改時間';
CREATE TABLE IF NOT EXISTS public.order_modified_logs
(
    order_modified_log_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    merchant_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    log_date date NOT NULL,
    log_type character varying(20) COLLATE pg_catalog."default" NOT NULL,
    channel_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    platform_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    record_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    record_timestamp timestamp with time zone,
    CONSTRAINT order_modified_logs_pkey PRIMARY KEY (order_modified_log_id, log_date)
) PARTITION BY RANGE (log_date);

ALTER TABLE public.order_modified_logs
    OWNER to oneec;

COMMENT ON TABLE public.order_modified_logs
    IS '訂單修改紀錄';

COMMENT ON COLUMN public.order_modified_logs.order_modified_log_id
    IS 'ID';

COMMENT ON COLUMN public.order_modified_logs.merchant_id
    IS '主表ID';

COMMENT ON COLUMN public.order_modified_logs.log_date
    IS '紀錄的日期';

COMMENT ON COLUMN public.order_modified_logs.log_type
    IS '打哪種的動作Log';

COMMENT ON COLUMN public.order_modified_logs.channel_id
    IS '通路ID';

COMMENT ON COLUMN public.order_modified_logs.platform_id
    IS '平台ID';

COMMENT ON COLUMN public.order_modified_logs.record_id
    IS '主要紀錄的資訊ID';

COMMENT ON COLUMN public.order_modified_logs.record_timestamp
    IS '紀錄時間';
CREATE TABLE public.channel_payment_methods
(
    channel_payment_method_id character varying(20) NOT NULL,
    channel_client_payment_method_id character varying(100)  NULL,
    channel_client_payment_method_name character varying(100) NOT NULL,
    status character varying(10) NOT NULL,
    platform character varying(20) NOT NULL,
    merchant_id character varying(20)  NULL,
    channel_id character varying(20)  NULL,
    other_data jsonb  NULL,
    
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone  NULL DEFAULT now(),
    PRIMARY KEY (channel_payment_method_id)
    
);

COMMENT ON TABLE public.channel_payment_methods IS '支付方法';

COMMENT ON COLUMN public.channel_payment_methods.channel_payment_method_id  IS 'ID';
COMMENT ON COLUMN public.channel_payment_methods.channel_client_payment_method_id IS '支付方法ID';
COMMENT ON COLUMN public.channel_payment_methods.channel_client_payment_method_name IS '支付方法名稱';
COMMENT ON COLUMN public.channel_payment_methods.status IS '是否啟用';
COMMENT ON COLUMN public.channel_payment_methods.platform IS '平台';
COMMENT ON COLUMN public.channel_payment_methods.other_data IS '其它不重要的';
COMMENT ON COLUMN public.channel_payment_methods.insert_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_payment_methods.insert_time IS '最後修改時間';
COMMENT ON COLUMN public.channel_payment_methods.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_payment_methods.modified_time IS '最後修改時間';

CREATE TABLE public.channel_delivery_methods
(
    channel_delivery_method_id character varying(20) NOT NULL,
    channel_client_delivery_method_id character varying(100)  NULL,
    channel_client_delivery_method_name character varying(100) NOT NULL,
    cod character varying(10) NOT NULL,
    status character varying(10) NOT NULL,
    platform character varying(20) NOT NULL,
    merchant_id character varying(20)  NULL,
    channel_id character varying(20)  NULL,
    other_data jsonb  NULL,
    
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone  NULL DEFAULT now(),
    PRIMARY KEY (channel_delivery_method_id)
    
);

COMMENT ON TABLE public.channel_delivery_methods IS '配送方法';

COMMENT ON COLUMN public.channel_delivery_methods.channel_delivery_method_id  IS 'ID';
COMMENT ON COLUMN public.channel_delivery_methods.channel_client_delivery_method_id IS '配送方法ID';
COMMENT ON COLUMN public.channel_delivery_methods.channel_client_delivery_method_name IS '配送方法名稱';
COMMENT ON COLUMN public.channel_delivery_methods.cod IS '是否貨到付款';
COMMENT ON COLUMN public.channel_delivery_methods.status IS '是否啟用';
COMMENT ON COLUMN public.channel_delivery_methods.platform IS '平台';
COMMENT ON COLUMN public.channel_delivery_methods.other_data IS '其它不重要的';
COMMENT ON COLUMN public.channel_delivery_methods.insert_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_delivery_methods.insert_time IS '最後修改時間';
COMMENT ON COLUMN public.channel_delivery_methods.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_delivery_methods.modified_time IS '最後修改時間';

CREATE TABLE public.channel_category_snapshots
(
    channel_category_snapshot_id character varying(20) NOT NULL,
    status character varying(10) NOT NULL,
    platform character varying(20) NOT NULL,
    merchant_id character varying(20)  NULL,
    category_trees jsonb  NULL,
    
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone  NULL DEFAULT now(),
    PRIMARY KEY (channel_category_snapshot_id)
    
);

COMMENT ON TABLE public.channel_category_snapshots IS '分類樹快照';

COMMENT ON COLUMN public.channel_category_snapshots.channel_category_snapshot_id  IS 'ID';
COMMENT ON COLUMN public.channel_category_snapshots.status IS '是否啟用';
COMMENT ON COLUMN public.channel_category_snapshots.platform IS '平台';
COMMENT ON COLUMN public.channel_category_snapshots.category_trees IS '其它不重要的';
COMMENT ON COLUMN public.channel_category_snapshots.insert_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_category_snapshots.insert_time IS '最後修改時間';
COMMENT ON COLUMN public.channel_category_snapshots.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_category_snapshots.modified_time IS '最後修改時間';

CREATE TABLE public.channel_brands
(
    channel_brand_id character varying(20) NOT NULL,
	channel_client_category_id character varying(100) NOT NULL,
    channel_client_brand_id character varying(100) NOT NULL,
    channel_client_brand_name character varying(100) NOT NULL,
    channel_client_brand_display_name JSONB  NULL,
    status character varying(10) NOT NULL,
    platform character varying(20) NOT NULL,
    merchant_id character varying(20)  NULL,
    other_data jsonb  NULL,    
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone  NULL DEFAULT now(),
    PRIMARY KEY (channel_brand_id)
    
);

COMMENT ON TABLE public.channel_brands IS '品牌選項';

COMMENT ON COLUMN public.channel_brands.channel_brand_id  IS 'ID';
COMMENT ON COLUMN public.channel_brands.channel_client_category_id IS '分類ID';
COMMENT ON COLUMN public.channel_brands.channel_client_brand_id IS '品牌ID';
COMMENT ON COLUMN public.channel_brands.channel_client_brand_name IS '品牌名稱';
COMMENT ON COLUMN public.channel_brands.channel_client_brand_display_name IS '品牌呈現';
COMMENT ON COLUMN public.channel_brands.status IS '是否啟用';
COMMENT ON COLUMN public.channel_brands.platform IS '平台';
COMMENT ON COLUMN public.channel_brands.merchant_id IS '平台';
COMMENT ON COLUMN public.channel_brands.other_data IS '其它不重要的';
COMMENT ON COLUMN public.channel_brands.insert_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_brands.insert_time IS '最後修改時間';
COMMENT ON COLUMN public.channel_brands.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.channel_brands.modified_time IS '最後修改時間';
CREATE TABLE public.convenience_store_settings
(
    convenience_store_setting_id character varying(20) NOT NULL,
	merchant_id character varying(20) NOT  NULL,
    platform_id character varying(20) NOT NULL,
    channel_id character varying(20) NOT NULL,
	
    convenience_store character varying(20) NOT NULL,
	platform_code character varying(20) NOT NULL,
	ship_code character varying(20) NOT NULL,
	label_merchant_name character varying(30) NOT NULL DEFAULT '',
    customer_service_phone character varying(30) NOT NULL  DEFAULT '',
    merchant_url character varying(2048) NOT NULL  DEFAULT '',
    label_download_file_id character varying(20) NOT NULL,
    label_download_file_time timestamp with time zone  NULL DEFAULT now(),
    
	status character varying(10) NOT NULL,
    insert_account_id character varying(20)  NULL ,
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone  NULL DEFAULT now(),
    PRIMARY KEY (convenience_store_setting_id)
    
);

COMMENT ON TABLE public.convenience_store_settings IS '超取標籤設定';

COMMENT ON COLUMN public.convenience_store_settings.convenience_store_setting_id  IS 'ID';
COMMENT ON COLUMN public.convenience_store_settings.merchant_id  IS 'Merchant ID';
COMMENT ON COLUMN public.convenience_store_settings.platform_id  IS 'channelSettingID';
COMMENT ON COLUMN public.convenience_store_settings.channel_id  IS 'channelID';
COMMENT ON COLUMN public.convenience_store_settings.convenience_store  IS '超商別';
COMMENT ON COLUMN public.convenience_store_settings.platform_code  IS '母代碼';
COMMENT ON COLUMN public.convenience_store_settings.ship_code  IS '子代碼';
COMMENT ON COLUMN public.convenience_store_settings.label_merchant_name  IS '廠商名稱';
COMMENT ON COLUMN public.convenience_store_settings.customer_service_phone  IS '客服專線';
COMMENT ON COLUMN public.convenience_store_settings.merchant_url  IS '店舖網址';
COMMENT ON COLUMN public.convenience_store_settings.label_download_file_id  IS '驗標的檔案ID';
COMMENT ON COLUMN public.convenience_store_settings.label_download_file_time  IS '驗標的檔案產生時間';
COMMENT ON COLUMN public.convenience_store_settings.status  IS '狀態';
COMMENT ON COLUMN public.convenience_store_settings.insert_account_id  IS '建檔人員編號';
COMMENT ON COLUMN public.convenience_store_settings.insert_time  IS '建檔時間';
COMMENT ON COLUMN public.convenience_store_settings.modified_account_id  IS '修改人員編號';
COMMENT ON COLUMN public.convenience_store_settings.modified_time  IS '最後修改時間';
----------------------------------------------------------------------------------------------------------------

CREATE TABLE public.merchant_discount_points
(
    merchant_discount_points_id character varying(20) NOT NULL,
	merchant_id character varying(20) NOT NULL,
    points integer NOT NULL,
    note varchar(256) NULL,
    merchant_bill_id varchar(20) NULL,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_discount_points_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);

CREATE INDEX merchant_discount_points_merchant_id_idx ON public.merchant_discount_points (merchant_id);

COMMENT ON TABLE public.merchant_discount_points IS '公司優惠點數';

COMMENT ON COLUMN public.merchant_discount_points.points IS '點數';
COMMENT ON COLUMN public.merchant_discount_points.note IS '備註';
COMMENT ON COLUMN public.merchant_discount_points.merchant_bill_id IS '關聯帳單';
COMMENT ON COLUMN public.merchant_discount_points.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant_discount_points.insert_time IS '建檔時間';
CREATE TABLE public.sell_item
(
    sell_item_id character varying(20) NOT NULL,
	name character varying(256) NOT NULL,
	charge_method character varying(20) NOT NULL,
    price decimal(12,2) NOT NULL DEFAULT 0,
	currency character varying(3) NOT NULL DEFAULT 'TWD',
	corp_item_number character varying(64),
	unit character varying(20) NOT NULL DEFAULT 'set',
	qty integer NOT NULL DEFAULT 1,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (sell_item_id)
);
COMMENT ON TABLE public.sell_item IS '銷售品項';

COMMENT ON COLUMN public.sell_item.sell_item_id IS '品項ID';
COMMENT ON COLUMN public.sell_item.name IS '名稱';
COMMENT ON COLUMN public.sell_item.charge_method IS '收費方式';
COMMENT ON COLUMN public.sell_item.price IS '未稅單價';
COMMENT ON COLUMN public.sell_item.currency IS '貨幣別';
COMMENT ON COLUMN public.sell_item.corp_item_number IS '會計科目';
COMMENT ON COLUMN public.sell_item.unit IS '計量單位';
COMMENT ON COLUMN public.sell_item.qty IS '數量';
COMMENT ON COLUMN public.sell_item.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.sell_item.insert_time IS '建檔時間';
COMMENT ON COLUMN public.sell_item.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.sell_item.modified_time IS '最後修改時間';
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('01', '體驗版', 'once', 6000.00, 'TWD', 'MISC501484', 'set', 1, 'cszj6P', '2023-10-24 16:14:08.169', 'cszj6P', '2023-10-24 16:14:08.167');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('02', '年付標準版', 'annually', 66000.00, 'TWD', 'MISC501485', 'set', 1, 'cszj6P', '2023-10-24 15:51:10.396', 'cszj6P', '2023-10-24 15:51:10.394');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('03', '年付專業版', 'annually', 88000.00, 'TWD', 'MISC501486', 'set', 1, 'cszj6P', '2023-10-24 15:51:37.504', 'cszj6P', '2023-10-24 15:51:37.501');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('04', '年付企業版', 'annually', 105000.00, 'TWD', 'MISC501487', 'set', 1, 'cszj6P', '2023-10-24 15:52:01.799', 'cszj6P', '2023-10-24 15:52:01.794');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('05', '月付輕量', 'monthly', 500.00, 'TWD', '', 'set', 1, 'cszj6P', '2023-10-24 15:52:24.460', 'cszj6P', '2023-10-24 15:52:24.457');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('100', '通路計量費(年付)', 'annually', 1200.00, 'TWD', 'MISC501488', 'set', 1, 'cszj6P', '2023-10-24 15:52:55.585', 'cszj6P', '2023-10-24 15:52:55.583');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('101', '通路計量費(月付)', 'monthly', 100.00, 'TWD', 'MISC501488', 'set', 1, 'cszj6P', '2023-10-24 15:53:43.090', 'cszj6P', '2023-10-24 15:53:43.088');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('102', '訂單處理費(月付)', 'monthly', 1.00, 'TWD', 'MISC501488', 'set', 1, 'cszj6P', '2023-10-24 15:54:05.961', 'cszj6P', '2023-10-24 15:54:05.958');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('200', 'OneEC Open API使用權(年付)', 'annually', 18000.00, 'TWD', 'MISC501488', 'set', 1, 'cszj6P', '2023-10-24 15:54:30.207', 'cszj6P', '2023-10-24 15:54:30.205');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('201', 'OneEC Open API使用權(月付)', 'monthly', 1500.00, 'TWD', 'MISC501488', 'set', 1, 'cszj6P', '2023-10-24 15:55:01.822', 'cszj6P', '2023-10-24 15:55:01.820');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('202', '一站出貨(年付)', 'annually', 0.00, 'TWD', '', 'set', 1, 'cszj6P', '2023-10-24 15:55:32.662', 'cszj6P', '2023-10-24 15:55:32.659');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('203', '一站出貨(月付)', 'monthly', 0.00, 'TWD', '', 'set', 1, 'cszj6P', '2023-10-24 15:55:52.539', 'cszj6P', '2023-10-24 15:55:52.537');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('204', '一站上架(年付)', 'annually', 0.00, 'TWD', '', 'set', 1, 'cszj6P', '2023-10-24 15:56:11.707', 'cszj6P', '2023-10-24 15:56:11.704');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('205', '一站上架(月付)', 'monthly', 0.00, 'TWD', '', 'set', 1, 'cszj6P', '2023-10-24 15:56:28.670', 'cszj6P', '2023-10-24 15:56:28.667');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('206', '智能電商(年付)', 'annually', 0.00, 'TWD', '', 'set', 1, 'cszj6P', '2023-10-24 15:56:48.394', 'cszj6P', '2023-10-24 15:56:48.390');
INSERT INTO public.sell_item (sell_item_id, "name", charge_method, price, currency, corp_item_number, unit, qty, insert_account_id, insert_time, modified_account_id, modified_time)
 VALUES('207', '智能電商(月付)', 'monthly', 0.00, 'TWD', '', 'set', 1, 'cszj6P', '2023-10-24 15:57:04.219', 'cszj6P', '2023-10-24 15:57:04.216');

CREATE TABLE public.merchant_buy_item
(
    merchant_buy_item_id character varying(20) NOT NULL,
	merchant_id character varying(20) NOT NULL,
	sell_item_id character varying(20) NOT NULL,
    qty integer NOT NULL,
	start_time timestamp with time zone NOT NULL,
	end_time timestamp with time zone NULL,
    note varchar(256) NULL,
	status character varying(20) NOT NULL DEFAULT 'enable',
    merchant_bill_id varchar(20) NULL,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_buy_item_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);

CREATE INDEX merchant_buy_item_merchant_id_idx ON public.merchant_buy_item (status, merchant_id);

COMMENT ON TABLE public.merchant_buy_item IS '公司購買品項';

COMMENT ON COLUMN public.merchant_buy_item.sell_item_id IS '品項ID';
COMMENT ON COLUMN public.merchant_buy_item.qty IS '數量';
COMMENT ON COLUMN public.merchant_buy_item.start_time IS '服務開始時間';
COMMENT ON COLUMN public.merchant_buy_item.end_time IS '服務結束時間';
COMMENT ON COLUMN public.merchant_buy_item.note IS '備註';
COMMENT ON COLUMN public.merchant_buy_item.status IS '狀態';
COMMENT ON COLUMN public.merchant_buy_item.merchant_bill_id IS '關聯帳單';
COMMENT ON COLUMN public.merchant_buy_item.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant_buy_item.insert_time IS '建檔時間';
COMMENT ON COLUMN public.merchant_buy_item.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.merchant_buy_item.modified_time IS '最後修改時間';

CREATE TABLE public.merchant_bill
(
    merchant_bill_id character varying(20) NOT NULL,
	merchant_id character varying(20) NOT NULL,
	type character varying(20) NOT NULL,
	status character varying(20) NOT NULL DEFAULT 'wait',
	buy_items jsonb NOT NULL,
	discount jsonb NULL,
	currency character varying(20) NOT NULL DEFAULT 'TWD',
    price decimal(12,2) NULL,
    tax_price decimal(12,2) NULL,
    real_pay_price decimal(12,2) NULL,
	pay_method character varying(20) NULL,
	real_pay_time timestamp with time zone NULL,
	deadline_pay_time timestamp with time zone NULL,
	item_start_time timestamp with time zone NOT NULL,
    note varchar(256) NULL,
    insert_account_id character varying(20),
    insert_time timestamp with time zone NOT NULL DEFAULT now(),
    modified_account_id character varying(20) NULL ,
    modified_time timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_bill_id),
    CONSTRAINT merchant_id_fk FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (merchant_id) MATCH SIMPLE
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        NOT VALID
);

CREATE INDEX merchant_bill_merchant_id_idx ON public.merchant_bill (status, merchant_id);

COMMENT ON TABLE public.merchant_bill IS '公司帳單';

COMMENT ON COLUMN public.merchant_bill.type IS '帳單類型';
COMMENT ON COLUMN public.merchant_bill.status IS '狀態';
COMMENT ON COLUMN public.merchant_bill.buy_items IS '購買項目';
COMMENT ON COLUMN public.merchant_bill.discount IS '折扣項目';
COMMENT ON COLUMN public.merchant_bill.currency IS '貨幣別';
COMMENT ON COLUMN public.merchant_bill.price IS '未稅總金額';
COMMENT ON COLUMN public.merchant_bill.tax_price IS '含稅總金額';
COMMENT ON COLUMN public.merchant_bill.real_pay_price IS '實際付款金額';
COMMENT ON COLUMN public.merchant_bill.pay_method IS '付款方式';
COMMENT ON COLUMN public.merchant_bill.real_pay_time IS '實際付款日期';
COMMENT ON COLUMN public.merchant_bill.deadline_pay_time IS '最晚付款日期';
COMMENT ON COLUMN public.merchant_bill.item_start_time IS '品項開始時間';
COMMENT ON COLUMN public.merchant_bill.note IS '備住';
COMMENT ON COLUMN public.merchant_bill.insert_account_id IS '建檔人員編號';
COMMENT ON COLUMN public.merchant_bill.insert_time IS '建檔時間';
COMMENT ON COLUMN public.merchant_bill.modified_account_id IS '修改人員編號';
COMMENT ON COLUMN public.merchant_bill.modified_time IS '最後修改時間';

CREATE TABLE public.merchant_info_statistic
(
	merchant_id character varying(20) NOT NULL,
    product_sum Integer NULL ,
    product_sum_time timestamp with time zone  NULL ,
    product_no_qty_sum Integer NULL ,
    product_no_qty_sum_time timestamp with time zone  NULL ,
    combination_product_setting_sum Integer NULL ,
    combination_product_setting_sum_time timestamp with time zone  NULL ,
    product_combination_flag_sum Integer NULL ,
    product_combination_flag_sum_time timestamp with time zone  NULL ,
    sell_pack_on_shelf_sum Integer NULL ,
    sell_pack_on_shelf_sum_time timestamp with time zone  NULL ,
    sell_pack_unset_sum Integer NULL ,
    sell_pack_unset_sum_time timestamp with time zone  NULL ,
    sell_pack_off_shelf_sum Integer NULL ,
    sell_pack_off_shelf_sum_time timestamp with time zone  NULL ,
    order_qty_monthly_last_year Integer NULL ,
    order_qty_monthly_last_year_time timestamp with time zone  NULL ,
    order_qty_monthly_this_year Integer NULL ,
    order_qty_monthly_this_year_time timestamp with time zone  NULL ,
    order_qty_last_month Integer NULL ,
    order_qty_last_month_time timestamp with time zone  NULL ,
    used_oneec_shipping BOOLEAN NULL ,
    used_oneec_shipping_time timestamp with time zone  NULL ,
    used_oneec_shipping_last_week BOOLEAN Null,
    used_oneec_shipping_last_week_time timestamp with time zone  NULL ,
    exclude_setting_products_sum  Integer NULL ,
    exclude_setting_products_sum_time timestamp with time zone  NULL ,
    activities_sum  Integer NULL ,
    activities_sum_time timestamp with time zone  NULL ,
    pickup_file_fail_sum Integer NULL ,
    pickup_file_fail_sum_time timestamp with time zone  NULL ,
    ship_sn_fail_sum Integer NULL ,
    ship_sn_fail_sum_time timestamp with time zone  NULL ,
    ship_document_fail_sum Integer NULL ,
    ship_document_fail_sum_time timestamp with time zone  NULL ,
    confirm_ship_sum Integer NULL ,
    confirm_ship_sum_time timestamp with time zone  NULL ,
    cancel_ship_sum Integer NULL ,
    cancel_ship_sum_time timestamp with time zone  NULL ,
    used_oneec_draft_product  BOOLEAN NULL ,
    used_oneec_draft_product_time timestamp with time zone  NULL ,
    used_oneec_draft_product_last_week  BOOLEAN NULL ,
    used_oneec_draft_product_last_week_time timestamp with time zone  NULL ,
    draft_sell_pack_status_waiting_for_delivery_sum  Integer NULL ,
    draft_sell_pack_status_waiting_for_delivery_sum_time timestamp with time zone  NULL ,
    draft_sell_pack_status_delivery_sum  Integer NULL ,
    draft_sell_pack_status_delivery_sum_time timestamp with time zone  NULL ,
    draft_sell_pack_status_delivery_failed_sum  Integer NULL ,
    draft_sell_pack_status_delivery_failed_sum_time timestamp with time zone  NULL ,
    draft_sell_pack_status_pass_sum Integer NULL ,
    draft_sell_pack_status_pass_sum_time timestamp with time zone  NULL ,
    draft_sell_pack_status_reviewing_sum Integer NULL ,
    draft_sell_pack_status_reviewing_sum_time timestamp with time zone  NULL ,
    draft_sell_pack_status_fail_sum Integer NULL ,
    draft_sell_pack_status_fail_sum_time timestamp with time zone  NULL ,
    PRIMARY KEY (merchant_id)
);
COMMENT ON TABLE public.merchant_info_statistic IS 'merchant 統計資訊';

COMMENT ON COLUMN public.merchant_info_statistic.merchant_id IS '對外ID';
COMMENT ON COLUMN public.merchant_info_statistic.product_sum IS 'Product總數';
COMMENT ON COLUMN public.merchant_info_statistic.product_sum_time IS 'Product總數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.product_no_qty_sum IS '無可接單量Product總數';
COMMENT ON COLUMN public.merchant_info_statistic.product_no_qty_sum_time IS '無可接單量Product總數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.combination_product_setting_sum IS '組合貨號綁定設定總數';
COMMENT ON COLUMN public.merchant_info_statistic.combination_product_setting_sum_time IS '組合貨號綁定設定總數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.product_combination_flag_sum IS 'Product有設組合總數';
COMMENT ON COLUMN public.merchant_info_statistic.product_combination_flag_sum_time IS 'Product有設組合總數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.sell_pack_on_shelf_sum IS 'SellPack上架中總數';
COMMENT ON COLUMN public.merchant_info_statistic.sell_pack_on_shelf_sum_time IS 'SellPack上架中總數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.sell_pack_unset_sum IS 'SellPack未設定總數';
COMMENT ON COLUMN public.merchant_info_statistic.sell_pack_unset_sum_time IS 'SellPack未設定總數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.sell_pack_off_shelf_sum IS 'SellPack下架中總數';
COMMENT ON COLUMN public.merchant_info_statistic.sell_pack_off_shelf_sum_time IS 'SellPack下架中總數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.order_qty_monthly_last_year IS '去年度所有訂單月均量';
COMMENT ON COLUMN public.merchant_info_statistic.order_qty_monthly_last_year_time IS '去年度所有訂單月均量_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.order_qty_monthly_this_year IS '本年度所有訂單月均量';
COMMENT ON COLUMN public.merchant_info_statistic.order_qty_monthly_this_year_time IS '本年度所有訂單月均量_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.order_qty_last_month IS '上個月所有訂單總量';
COMMENT ON COLUMN public.merchant_info_statistic.order_qty_last_month_time IS '上個月所有訂單總量_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.used_oneec_shipping IS '曾使用一站出貨';
COMMENT ON COLUMN public.merchant_info_statistic.used_oneec_shipping_time IS '曾使用一站出貨_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.used_oneec_shipping_last_week IS '上週有用一站出貨';
COMMENT ON COLUMN public.merchant_info_statistic.used_oneec_shipping_last_week_time IS '上週有用一站出貨_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.exclude_setting_products_sum IS '自動變量排除設定筆數';
COMMENT ON COLUMN public.merchant_info_statistic.exclude_setting_products_sum_time IS '自動變量排除設定筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.activities_sum IS '出貨贈品設定筆數';
COMMENT ON COLUMN public.merchant_info_statistic.activities_sum_time IS '出貨贈品設定筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.pickup_file_fail_sum IS '撿貨失敗筆數';
COMMENT ON COLUMN public.merchant_info_statistic.pickup_file_fail_sum_time IS '撿貨失敗筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.ship_sn_fail_sum IS '取號失敗筆數';
COMMENT ON COLUMN public.merchant_info_statistic.ship_sn_fail_sum_time IS '取號失敗筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.ship_document_fail_sum IS '配送單失敗筆數';
COMMENT ON COLUMN public.merchant_info_statistic.ship_document_fail_sum_time IS '配送單失敗筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.confirm_ship_sum IS '確認出貨失敗筆數';
COMMENT ON COLUMN public.merchant_info_statistic.confirm_ship_sum_time IS '確認出貨失敗筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.cancel_ship_sum IS '取消出貨失敗筆數';
COMMENT ON COLUMN public.merchant_info_statistic.cancel_ship_sum_time IS '取消出貨失敗筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.used_oneec_draft_product IS '曾經過用一站上架';
COMMENT ON COLUMN public.merchant_info_statistic.used_oneec_draft_product_time IS '曾經過用一站上架_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_waiting_for_delivery_sum IS '提品等待中總筆數';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_waiting_for_delivery_sum_time IS '提品等待中總筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_delivery_sum IS '提品送件中總筆數';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_delivery_sum_time IS '提品送件中總筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_delivery_failed_sum IS '提品送件失敗總筆數';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_delivery_failed_sum_time IS '提品送件失敗總筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_pass_sum IS '提品審核通過總筆數';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_pass_sum_time IS '提品審核通過總筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_reviewing_sum IS '提品審核中總筆數';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_reviewing_sum_time IS '提品審核中總筆數_計算時間';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_fail_sum IS '提品審核不通過總筆數';
COMMENT ON COLUMN public.merchant_info_statistic.draft_sell_pack_status_fail_sum_time IS '提品審核不通過總筆數_計算時間';
CREATE TABLE IF NOT EXISTS public.sell_pack_recommend_quantities_logs
(
    sell_pack_recommend_quantities_log_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    sell_pack_recommend_quantiy_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    record_date date NOT NULL,
    merchant_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    platform_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    channel_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    product_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    sell_pack_id character varying(20) COLLATE pg_catalog."default" NOT NULL,
    item_number character varying(512) COLLATE pg_catalog."default" NOT NULL,
    product_name character varying(512) COLLATE pg_catalog."default" NOT NULL,
    product_spec_name character varying(512) COLLATE pg_catalog."default",
    product_qty integer NOT NULL,
    product_on_the_way_qty integer,
    product_configurable_qty integer,
    sell_pack_records_total_qty integer NOT NULL,
    sell_pack_channel_name character varying(512) COLLATE pg_catalog."default",
    sell_pack_channel_items_id character varying(512) COLLATE pg_catalog."default" NOT NULL,
    sell_pack_channel_spec_id character varying(512) COLLATE pg_catalog."default" NULL,
    sell_pack_shelf_status character varying(20) COLLATE pg_catalog."default" NOT NULL,
    sell_pack_warehouse_type character varying(20) COLLATE pg_catalog."default" NOT NULL,
    sell_pack_qty integer NOT NULL,
    recommend_qty integer NOT NULL,
    insert_dt timestamp with time zone NOT NULL,
    triggered_to_notify boolean NOT NULL,
    channel_api_notify_is_succeed boolean,
    channel_api_notify_succeed_dt timestamp with time zone,
    CONSTRAINT sell_pack_recommend_quantities_log_pkey PRIMARY KEY (sell_pack_recommend_quantities_log_id, record_date)
) PARTITION BY RANGE (record_date);

ALTER TABLE IF EXISTS public.sell_pack_recommend_quantities_logs
    OWNER to oneec;

COMMENT ON TABLE public.sell_pack_recommend_quantities_logs
    IS '智能配量歷程表';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_recommend_quantities_log_id
    IS 'SYSTEX_Unique_Key';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_recommend_quantiy_id
    IS 'sell_pack_recommend_quantiy表的ID';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.record_date
    IS '紀錄日期';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.merchant_id
    IS 'merchant編號';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.platform_id
    IS '通路設定ID';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.channel_id
    IS '公司通路設定';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.product_id
    IS 'Product_ID';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_id
    IS 'Sellpack_ID';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.item_number
    IS '商品貨號';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.product_name
    IS '商品名稱';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.product_spec_name
    IS '規格';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.product_qty
    IS 'SKU可接單量';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.product_on_the_way_qty
    IS 'SKU待出貨量';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.product_configurable_qty
    IS 'SKU可配置量';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_records_total_qty
    IS 'SKU賣場檔總筆數';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_channel_name
    IS '配量通路';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_channel_items_id
    IS '配量通路賣場編號';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_channel_spec_id
    IS '配量通路規格編號';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_shelf_status
    IS '配量前賣場狀態';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_warehouse_type
    IS '配量前賣場屬性';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.sell_pack_qty
    IS '配量前通路賣場可接單量';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.recommend_qty
    IS '配量後接單量建議值';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.insert_dt
    IS '配量計算時間';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.triggered_to_notify
    IS '是否觸發通知';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.channel_api_notify_is_succeed
    IS '配量通知平台成功打是否成功';

COMMENT ON COLUMN public.sell_pack_recommend_quantities_logs.channel_api_notify_succeed_dt
    IS '配量通知平台成功時間';
CREATE TABLE sell_pack_recommend_quantities_logs_default PARTITION OF sell_pack_recommend_quantities_logs DEFAULT;
