-- Migration: Add platform_id and restructure channel_sync_logs table
-- Date: 2026-02-25
-- Description:
--   - Add platform_id column (FK to platform.id) - required
--   - Change merchant_id and channel_id to nullable
--   - Add http_status column
--   - Add foreign key constraints
--   - Update indexes to include platform_id

-- Step 1: Add platform_id column if not exists
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'channel_sync_logs' AND column_name = 'platform_id'
    ) THEN
        ALTER TABLE public.channel_sync_logs
        ADD COLUMN platform_id VARCHAR(20);

        -- Create index for platform_id
        CREATE INDEX idx_sync_log_platform ON public.channel_sync_logs (platform_id, created_at DESC);
    END IF;
END $$;

-- Step 2: Add http_status column if not exists
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'channel_sync_logs' AND column_name = 'http_status'
    ) THEN
        ALTER TABLE public.channel_sync_logs
        ADD COLUMN http_status INTEGER;
    END IF;
END $$;

-- Step 3: Modify merchant_id to be nullable
DO $$
BEGIN
    EXECUTE 'ALTER TABLE public.channel_sync_logs ALTER COLUMN merchant_id DROP NOT NULL';
EXCEPTION WHEN OTHERS THEN
    -- Column already nullable, skip
    NULL;
END $$;

-- Step 4: Modify channel_id to be nullable
DO $$
BEGIN
    EXECUTE 'ALTER TABLE public.channel_sync_logs ALTER COLUMN channel_id DROP NOT NULL';
EXCEPTION WHEN OTHERS THEN
    -- Column already nullable, skip
    NULL;
END $$;

-- Step 5: Set platform_id to NOT NULL constraint (after data migration)
-- Note: In production, you need to first populate platform_id for existing records
-- before adding the NOT NULL constraint. This requires a data migration script.
-- For now, this step is commented out. Uncomment after data migration is complete.
/*
DO $$
BEGIN
    EXECUTE 'ALTER TABLE public.channel_sync_logs ALTER COLUMN platform_id SET NOT NULL';
EXCEPTION WHEN OTHERS THEN
    -- Already NOT NULL, skip
    NULL;
END $$;
*/

-- Step 6: Add foreign key constraints if not exists
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'channel_sync_logs' AND constraint_name = 'fk_sync_log_platform'
    ) THEN
        ALTER TABLE public.channel_sync_logs
        ADD CONSTRAINT fk_sync_log_platform FOREIGN KEY (platform_id)
            REFERENCES public.platform (id) ON UPDATE CASCADE ON DELETE NO ACTION;
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- Constraint already exists or other error, skip
    NULL;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'channel_sync_logs' AND constraint_name = 'fk_sync_log_merchant'
    ) THEN
        ALTER TABLE public.channel_sync_logs
        ADD CONSTRAINT fk_sync_log_merchant FOREIGN KEY (merchant_id)
            REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION;
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- Constraint already exists or other error, skip
    NULL;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'channel_sync_logs' AND constraint_name = 'fk_sync_log_channel'
    ) THEN
        ALTER TABLE public.channel_sync_logs
        ADD CONSTRAINT fk_sync_log_channel FOREIGN KEY (channel_id)
            REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE NO ACTION;
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- Constraint already exists or other error, skip
    NULL;
END $$;

-- Note: After Flyway applies this migration, you need to run a separate data migration
-- to populate platform_id for existing records before enabling the NOT NULL constraint.
-- See: V202602252_populate_platform_id_data_migration.sql
