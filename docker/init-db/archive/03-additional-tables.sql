-- SimpleEC OMS - Additional tables for Phase 0-3

-- Daily statistics (pre-aggregated, partition by stat_date)
CREATE TABLE IF NOT EXISTS public.daily_statistics (
    id BIGSERIAL,
    merchant_id VARCHAR(20) NOT NULL,
    channel_id BIGINT,
    stat_date DATE NOT NULL,
    order_count INTEGER DEFAULT 0,
    total_amount NUMERIC(15,2) DEFAULT 0,
    shipped_count INTEGER DEFAULT 0,
    completed_count INTEGER DEFAULT 0,
    cancelled_count INTEGER DEFAULT 0,
    refund_count INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (id, stat_date)
) PARTITION BY RANGE (stat_date);

-- Create initial partitions
CREATE TABLE IF NOT EXISTS public.daily_statistics_y2026m01 PARTITION OF public.daily_statistics
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE IF NOT EXISTS public.daily_statistics_y2026m02 PARTITION OF public.daily_statistics
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE IF NOT EXISTS public.daily_statistics_y2026m03 PARTITION OF public.daily_statistics
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE UNIQUE INDEX IF NOT EXISTS idx_daily_stats_unique
    ON public.daily_statistics (merchant_id, stat_date, channel_id);

COMMENT ON TABLE public.daily_statistics IS '每日統計（預彙整，merchant × date × channel）';

-- Failed task logs
CREATE TABLE IF NOT EXISTS public.failed_task_logs (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(50),
    task_type VARCHAR(50),
    task_action VARCHAR(100),
    source_job_type VARCHAR(50),
    merchant_id VARCHAR(20),
    owner_id VARCHAR(50),
    original_topic VARCHAR(100),
    original_key VARCHAR(200),
    error_message TEXT,
    reason VARCHAR(50),
    retry_count INTEGER DEFAULT 0,
    payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_failed_task_created ON public.failed_task_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_failed_task_action ON public.failed_task_logs (task_action);

COMMENT ON TABLE public.failed_task_logs IS '失敗任務 LOG（RD 定時查看清理）';

-- Add health column to channel_sync_logs if it exists
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'channel_sync_logs') THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'channel_sync_logs' AND column_name = 'health') THEN
            ALTER TABLE public.channel_sync_logs ADD COLUMN health VARCHAR(20);
        END IF;
    END IF;
END $$;
