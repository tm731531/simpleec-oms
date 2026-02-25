-- Data Migration: Populate platform_id for existing channel_sync_logs records
-- Date: 2026-02-25
-- Description:
--   1. For CHANNEL_HEALTH_CHECK records: get platform_id from the channel
--   2. For PLATFORM_HEALTH_CHECK records: requires manual mapping or deletion
--
-- Note: PLATFORM_HEALTH_CHECK records created before this migration lack platformCode info,
-- making it impossible to determine which platform they belong to.
-- Options:
--   a) Delete all old PLATFORM_HEALTH_CHECK records (recommended)
--   b) Manually map them based on creation timestamps
--   c) Set a default platform_id for all old records

-- Step 1: Populate platform_id for CHANNEL_HEALTH_CHECK records
-- These records should have a channel_id, so we can join with channel table to get platform_id
UPDATE public.channel_sync_logs csl
SET platform_id = c.platform_id
WHERE csl.sync_type = 'CHANNEL_HEALTH_CHECK'
  AND csl.channel_id IS NOT NULL
  AND csl.platform_id IS NULL
  AND EXISTS (
    SELECT 1 FROM public.channel c
    WHERE c.id = csl.channel_id
  );

-- Step 2: For PLATFORM_HEALTH_CHECK records, we have two options:
-- Option A: Delete all old PLATFORM_HEALTH_CHECK records (RECOMMENDED)
--           These are just health check logs and losing historical data is acceptable
DELETE FROM public.channel_sync_logs
WHERE sync_type = 'PLATFORM_HEALTH_CHECK'
  AND platform_id IS NULL;

-- Option B: If you want to keep the records, you would need to manually map them
-- by examining timestamps and patterns. This requires business logic outside the SQL.
-- For now, we recommend Option A (deletion).

-- After this migration completes, the platform_id column should be fully populated
-- for all meaningful records (CHANNEL_HEALTH_CHECK and any SYNC_* records that reference channels).

-- Log the result
DO $$
DECLARE
    v_channel_health_count INT;
    v_platform_health_count INT;
BEGIN
    SELECT COUNT(*) INTO v_channel_health_count
    FROM public.channel_sync_logs
    WHERE sync_type = 'CHANNEL_HEALTH_CHECK' AND platform_id IS NOT NULL;

    SELECT COUNT(*) INTO v_platform_health_count
    FROM public.channel_sync_logs
    WHERE sync_type = 'PLATFORM_HEALTH_CHECK' AND platform_id IS NOT NULL;

    RAISE NOTICE 'Data migration completed:';
    RAISE NOTICE '  - CHANNEL_HEALTH_CHECK records with platform_id: %', v_channel_health_count;
    RAISE NOTICE '  - PLATFORM_HEALTH_CHECK records with platform_id: %', v_platform_health_count;
END $$;
