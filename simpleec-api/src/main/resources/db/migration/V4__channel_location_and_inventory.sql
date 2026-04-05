-- V4: Multi-location inventory support
-- channel_location: platform warehouse/location registry per channel
-- sell_pack_inventory: per-location quantity snapshot for each sell_pack

CREATE TABLE public.channel_location (
    id                   VARCHAR(20)  NOT NULL,
    merchant_id          VARCHAR(20)  NOT NULL,
    channel_id           VARCHAR(20)  NOT NULL,
    platform_location_id VARCHAR(256) NOT NULL,  -- e.g. Shopify location_id
    location_name        VARCHAR(256),
    is_sync_target       BOOLEAN      NOT NULL DEFAULT false,
    -- When OMS pushes UPDATE_INVENTORY, only sync_target location is updated.
    -- For platforms with no location concept (Shopee, Cyberbiz), this table has no rows.
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_channel_location_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT uq_channel_location UNIQUE (channel_id, platform_location_id)
);

CREATE INDEX idx_channel_location_channel ON public.channel_location (channel_id);
CREATE INDEX idx_channel_location_sync_target ON public.channel_location (channel_id, is_sync_target);

-- Only one sync target allowed per channel
CREATE UNIQUE INDEX idx_channel_location_one_sync_target
    ON public.channel_location (channel_id)
    WHERE is_sync_target = true;

---

CREATE TABLE public.sell_pack_inventory (
    id                  VARCHAR(20)  NOT NULL,
    sell_pack_id        VARCHAR(20)  NOT NULL,
    channel_location_id VARCHAR(20),
    -- NULL = platform has no location concept (Shopee, Cyberbiz, Shopline, etc.)
    -- Non-NULL = Shopify (or future multi-location platforms), FK → channel_location
    quantity            INTEGER      NOT NULL DEFAULT 0,
    last_synced_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_sell_pack_inventory_pack FOREIGN KEY (sell_pack_id)
        REFERENCES public.sell_pack (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_sell_pack_inventory_location FOREIGN KEY (channel_location_id)
        REFERENCES public.channel_location (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT uq_sell_pack_inventory UNIQUE (sell_pack_id, COALESCE(channel_location_id, ''))
);

CREATE INDEX idx_sell_pack_inventory_pack ON public.sell_pack_inventory (sell_pack_id);
