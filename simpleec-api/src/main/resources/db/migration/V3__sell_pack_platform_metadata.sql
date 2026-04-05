-- V3: Add platform_metadata JSONB to sell_pack
-- Stores platform-specific IDs that don't fit the generic schema.
-- Example (Shopify):
--   { "shopify": { "inventory_item_id": "457924702", "location_id": "905684977" } }

ALTER TABLE public.sell_pack
    ADD COLUMN IF NOT EXISTS platform_metadata JSONB;

COMMENT ON COLUMN public.sell_pack.platform_metadata IS
    'Platform-specific metadata (e.g. Shopify inventory_item_id, location_id). Keyed by platform name.';
