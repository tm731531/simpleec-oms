-- V5: Add capabilities JSONB to platform table
-- Replaces hardcoded platform-name checks with data-driven capability flags.
--
-- Known capability keys:
--   multiLocation  boolean  true = platform has per-location inventory (e.g. Shopify)
--   webhook        boolean  true = platform pushes events via webhook
--   asyncInventory boolean  true = inventory update is async (callback required)
--
-- Default for all existing rows: empty object (no special capabilities).
-- Seed values are set below for known platforms.

ALTER TABLE public.platform
    ADD COLUMN IF NOT EXISTS capabilities JSONB NOT NULL DEFAULT '{}';

-- Shopify: multi-location inventory
UPDATE public.platform
SET capabilities = '{"multiLocation": true}'
WHERE platform_name = 'shopify';

-- All other platforms: no multi-location (default '{}' already set)
