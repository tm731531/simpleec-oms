-- V6: Fixes from comprehensive review (2026-04-06)
--
-- DB-C2: Fix sell_pack_inventory UNIQUE constraint
--   COALESCE() in inline UNIQUE constraints is non-standard; replace with partial indexes.
--
-- DB-C3: Add missing columns to refund_orders
--   channel_id, channel_order_id, currency — required for Translation Layer compliance
--   and cross-channel dedup (DB-C4: findByChannelRefundId needs channel_id scope).
--
-- ARCH-C1: Add outbound-action capability flags to Cyberbiz platform
--   supportsShipment, supportsReturnApproval, supportsReturnFetch

-- ============================================================
-- DB-C2: Replace COALESCE UNIQUE constraint with partial indexes
-- ============================================================

-- Drop the inline constraint (may or may not exist depending on PG version)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_sell_pack_inventory'
          AND conrelid = 'public.sell_pack_inventory'::regclass
    ) THEN
        ALTER TABLE public.sell_pack_inventory DROP CONSTRAINT uq_sell_pack_inventory;
    END IF;
END $$;

-- Also drop any old partial indexes if they already exist (idempotent re-run)
DROP INDEX IF EXISTS public.uq_sell_pack_inv_no_loc;
DROP INDEX IF EXISTS public.uq_sell_pack_inv_with_loc;

-- Unique: one row per sell_pack with no location (platforms without multi-location)
CREATE UNIQUE INDEX uq_sell_pack_inv_no_loc
    ON public.sell_pack_inventory (sell_pack_id)
    WHERE channel_location_id IS NULL;

-- Unique: one row per (sell_pack, location) for multi-location platforms (e.g. Shopify)
CREATE UNIQUE INDEX uq_sell_pack_inv_with_loc
    ON public.sell_pack_inventory (sell_pack_id, channel_location_id)
    WHERE channel_location_id IS NOT NULL;

-- ============================================================
-- DB-C3: Add missing columns to refund_orders
-- ============================================================

ALTER TABLE public.refund_orders
    ADD COLUMN IF NOT EXISTS channel_id       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS channel_order_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS currency         VARCHAR(3) NOT NULL DEFAULT 'TWD';

-- FK: channel_id → channel.id (nullable; SET NULL on channel delete)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_refund_channel'
          AND conrelid = 'public.refund_orders'::regclass
    ) THEN
        ALTER TABLE public.refund_orders
            ADD CONSTRAINT fk_refund_channel FOREIGN KEY (channel_id)
                REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE SET NULL;
    END IF;
END $$;

-- Index: channel_id for fast scoped lookups
CREATE INDEX IF NOT EXISTS idx_refund_channel
    ON public.refund_orders (channel_id);

-- Index: (channel_id, channel_refund_id) for dedup queries — fixes DB-C4
CREATE INDEX IF NOT EXISTS idx_refund_channel_refund_id
    ON public.refund_orders (channel_id, channel_refund_id);

-- ============================================================
-- ARCH-C1: Add outbound-action capability flags to Cyberbiz
-- ============================================================

UPDATE public.platform
SET capabilities = capabilities || '{
    "supportsShipment": true,
    "supportsReturnApproval": true,
    "supportsReturnFetch": true
}'::jsonb
WHERE platform_name = 'cyberbiz';
