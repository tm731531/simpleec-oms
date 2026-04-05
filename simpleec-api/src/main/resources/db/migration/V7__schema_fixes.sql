-- V7: Schema entity-alignment fixes
--
-- Fix: platform_account.status column missing from DB
--   Entity declares status VARCHAR(20) NOT NULL DEFAULT 'enable'
--   Column was missing from V1 initial schema

ALTER TABLE public.platform_account
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'enable';
