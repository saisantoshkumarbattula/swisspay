-- V2: Fix currency columns from bpchar (CHAR) to varchar to match Hibernate expectations.
-- PostgreSQL stores CHAR(n) as bpchar internally; Hibernate's String mapping expects varchar.
-- This migration is safe to run on an existing DB; VARCHAR(3) is functionally equivalent.

ALTER TABLE wallets   ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payments  ALTER COLUMN currency TYPE VARCHAR(3);

-- V2: Seed the two wallet rows that V1 failed to insert due to invalid UUID syntax.
-- The invalid UUID rows ('c2ggde77...' and 'd3hhef66...') never made it into the DB,
-- so we insert the corrected UUIDs here.
INSERT INTO wallets (user_id, balance, currency) VALUES
    ('c2ccde77-2e2d-4cf0-dd8f-8dd1df502c33', 2500.0000, 'USD'),
    ('d3ddef66-3f3e-4de1-ae9a-9ee2ea613d44', 500.0000,  'USD')
ON CONFLICT (user_id) DO NOTHING;
