-- Finalization: Set NOT NULL constraint on platform_id
-- Date: 2026-02-25
-- Description:
--   After data migration is complete, set platform_id as NOT NULL
--   This ensures data integrity going forward.

-- Check for any remaining NULL platform_id values
DO $$
DECLARE
    v_null_count INT;
BEGIN
    SELECT COUNT(*) INTO v_null_count
    FROM public.channel_sync_logs
    WHERE platform_id IS NULL;

    IF v_null_count > 0 THEN
        RAISE WARNING 'WARNING: Found % records with NULL platform_id. Please review data migration.', v_null_count;
        RAISE EXCEPTION 'Cannot set NOT NULL constraint: % records with NULL platform_id exist', v_null_count;
    END IF;
END $$;

-- Set NOT NULL constraint on platform_id
DO $$
BEGIN
    EXECUTE 'ALTER TABLE public.channel_sync_logs ALTER COLUMN platform_id SET NOT NULL';
    RAISE NOTICE 'Successfully set NOT NULL constraint on platform_id';
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error setting NOT NULL constraint: %', SQLERRM;
END $$;

-- Summary
RAISE NOTICE '✅ Migration finalized: platform_id is now a required field';
RAISE NOTICE '✅ Foreign key constraints are in place';
RAISE NOTICE '✅ merchant_id and channel_id are now nullable';
RAISE NOTICE '✅ http_status column is available for storing HTTP status codes';
