-- V2: Fix currency columns from bpchar (CHAR) to varchar to match Hibernate expectations.
-- PostgreSQL stores CHAR(n) as bpchar internally; Hibernate's String mapping expects varchar.
-- This migration is safe to run on an existing DB; VARCHAR(3) is functionally equivalent for fixed-length codes.

ALTER TABLE wallets   ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payments  ALTER COLUMN currency TYPE VARCHAR(3);
