-- V3__add_platform_id_to_channel_sync_logs.sql
-- 目的：讓 channel_sync_logs 支援平台檢查（無 channel_id），移除多餘的 merchant_id

-- 1. 讓 channel_id 可為 NULL（原本就是，但明確聲明）
ALTER TABLE public.channel_sync_logs ALTER COLUMN channel_id DROP NOT NULL;

-- 2. 移除 merchant_id 欄位（多餘，可從 channel 表 JOIN 獲得）
ALTER TABLE public.channel_sync_logs DROP COLUMN IF EXISTS merchant_id;

-- 3. 新增 platform_id 欄位
ALTER TABLE public.channel_sync_logs ADD COLUMN IF NOT EXISTS platform_id VARCHAR(20);

-- 4. 新增索引
CREATE INDEX IF NOT EXISTS idx_sync_log_platform ON public.channel_sync_logs (platform_id, created_at DESC);

-- 5. 移除舊的 merchant 索引
DROP INDEX IF EXISTS idx_sync_log_merchant;

-- 6. 修正現有的 PLATFORM_HEALTH_CHECK 記錄
UPDATE public.channel_sync_logs
SET channel_id = NULL, platform_id = 'shopee'
WHERE sync_type = 'PLATFORM_HEALTH_CHECK' AND channel_id = 'PLATFORM_CHECK';
