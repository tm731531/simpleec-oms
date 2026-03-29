-- V2: Replace order_shipments with full shipment workflow tables
-- Spec: docs/superpowers/specs/2026-03-29-shipment-workflow-design.md

-- -------------------------------------------------------------------------
-- shipment_batches — vehicle/dispatch run (optional, batch_mode merchants)
-- -------------------------------------------------------------------------
CREATE TABLE public.shipment_batches (
    id                   VARCHAR(20)    NOT NULL,
    merchant_id          VARCHAR(20)    NOT NULL,
    batch_no             VARCHAR(50),
    carrier              VARCHAR(100),
    logistics_cost       DECIMAL(10,2),
    scheduled_pickup_at  TIMESTAMPTZ,
    actual_pickup_at     TIMESTAMPTZ,
    carrier_driver_id    VARCHAR(100),
    handoff_box_count    INTEGER,
    status               VARCHAR(30)    NOT NULL DEFAULT 'PREPARING',
    force_ready_note     TEXT,
    notes                TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_batch_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION
);
CREATE INDEX idx_shipment_batches_merchant ON public.shipment_batches (merchant_id);

-- -------------------------------------------------------------------------
-- shipments — one per physical box (tracking number)
-- -------------------------------------------------------------------------
CREATE TABLE public.shipments (
    id                    VARCHAR(20)    NOT NULL,
    merchant_id           VARCHAR(20)    NOT NULL,
    channel_id            VARCHAR(20)    NOT NULL,
    batch_id              VARCHAR(20),
    shipment_no           VARCHAR(50),
    tracking_number       VARCHAR(100),
    carrier               VARCHAR(100),
    logistics_cost        DECIMAL(10,2),
    status                VARCHAR(30)    NOT NULL DEFAULT 'PICKING_LIST',
    has_exception         BOOLEAN        NOT NULL DEFAULT FALSE,
    exception_type        VARCHAR(30),
    exception_note        TEXT,
    dispatched_at         TIMESTAMPTZ,
    platform_notified_at  TIMESTAMPTZ,
    cancelled_at          TIMESTAMPTZ,
    cancel_reason         TEXT,
    notes                 TEXT,
    version               INTEGER        NOT NULL DEFAULT 0,
    shipment_type         VARCHAR(30)    NOT NULL DEFAULT 'HOME_DELIVERY',
    cvs_store_code        VARCHAR(20),
    cvs_store_name        VARCHAR(100),
    cvs_recipient_name    VARCHAR(100),
    cvs_phone_last5       VARCHAR(5),
    is_cod                BOOLEAN        NOT NULL DEFAULT FALSE,
    cod_amount            DECIMAL(10,2),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_merchant FOREIGN KEY (merchant_id)
        REFERENCES public.merchant (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_shipment_channel FOREIGN KEY (channel_id)
        REFERENCES public.channel (id) ON UPDATE CASCADE ON DELETE NO ACTION,
    CONSTRAINT fk_shipment_batch FOREIGN KEY (batch_id)
        REFERENCES public.shipment_batches (id) ON UPDATE CASCADE ON DELETE SET NULL
);
CREATE INDEX idx_shipments_merchant      ON public.shipments (merchant_id);
CREATE INDEX idx_shipments_batch         ON public.shipments (batch_id);
CREATE INDEX idx_shipments_status        ON public.shipments (merchant_id, status);
CREATE INDEX idx_shipments_merchant_date ON public.shipments (merchant_id, created_at DESC);
CREATE INDEX idx_shipments_channel       ON public.shipments (channel_id);

-- -------------------------------------------------------------------------
-- shipment_items — order line-items allocated to a box
-- -------------------------------------------------------------------------
CREATE TABLE public.shipment_items (
    id               VARCHAR(20)  NOT NULL,
    shipment_id      VARCHAR(20)  NOT NULL,
    merchant_id      VARCHAR(20)  NOT NULL,
    order_id         VARCHAR(20)  NOT NULL,
    channel_order_id VARCHAR(100),
    items            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_item_shipment FOREIGN KEY (shipment_id)
        REFERENCES public.shipments (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_shipment_item_order FOREIGN KEY (order_id)
        REFERENCES public.orders (id) ON UPDATE CASCADE ON DELETE NO ACTION
);
CREATE INDEX idx_shipment_items_shipment ON public.shipment_items (shipment_id);
CREATE INDEX idx_shipment_items_order    ON public.shipment_items (order_id);
CREATE INDEX idx_shipment_items_merchant ON public.shipment_items (merchant_id);
CREATE INDEX idx_shipment_items_gin      ON public.shipment_items USING GIN (items);

-- -------------------------------------------------------------------------
-- shipment_status_logs — audit trail for status changes
-- -------------------------------------------------------------------------
CREATE TABLE public.shipment_status_logs (
    id           VARCHAR(20)  NOT NULL,
    shipment_id  VARCHAR(20)  NOT NULL,
    from_status  VARCHAR(30),
    to_status    VARCHAR(30)  NOT NULL,
    operator_id  VARCHAR(100),
    remark       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_status_log FOREIGN KEY (shipment_id)
        REFERENCES public.shipments (id) ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX idx_shipment_status_log ON public.shipment_status_logs (shipment_id);

-- -------------------------------------------------------------------------
-- Migrate existing order_shipments → shipments (all as DISPATCHED)
-- -------------------------------------------------------------------------
INSERT INTO public.shipments
    (id, merchant_id, channel_id, shipment_no, tracking_number, carrier,
     status, dispatched_at, platform_notified_at, created_at, updated_at)
SELECT
    os.id,
    o.merchant_id,
    o.channel_id,
    'LEGACY-' || os.id,
    os.tracking_number,
    os.logistics_company,
    'DISPATCHED',
    os.shipped_at,
    os.shipped_at,
    os.created_at,
    os.created_at
FROM public.order_shipments os
JOIN public.orders o ON o.id = os.order_id;

-- -------------------------------------------------------------------------
-- Drop order_shipments (replaced by shipments)
-- -------------------------------------------------------------------------
DROP TABLE IF EXISTS public.order_shipments;
