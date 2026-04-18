-- SwiftPay Ledger Service Schema
-- Shared with transaction-gateway; Flyway ensures idempotency via checksums.

-- Wallets table (canonical source of truth for balances)
CREATE TABLE IF NOT EXISTS wallets (
    user_id    UUID          NOT NULL,
    balance    NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency   CHAR(3)       NOT NULL DEFAULT 'USD',
    version    BIGINT        NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_wallets PRIMARY KEY (user_id),
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0)
);

-- Payments table
CREATE TABLE IF NOT EXISTS payments (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key  VARCHAR(64)   NOT NULL,
    sender_id        UUID          NOT NULL,
    receiver_id      UUID          NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    currency         CHAR(3)       NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    failure_reason   VARCHAR(512),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_payments            PRIMARY KEY (id),
    CONSTRAINT uq_payments_idem_key   UNIQUE (idempotency_key),
    CONSTRAINT fk_payments_sender     FOREIGN KEY (sender_id)   REFERENCES wallets(user_id),
    CONSTRAINT fk_payments_receiver   FOREIGN KEY (receiver_id) REFERENCES wallets(user_id),
    CONSTRAINT chk_payments_amount    CHECK (amount > 0),
    CONSTRAINT chk_payments_status    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Ledger entries: immutable double-entry bookkeeping records
CREATE TABLE IF NOT EXISTS ledger_entries (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    payment_id     UUID          NOT NULL,
    wallet_id      UUID          NOT NULL,
    entry_type     VARCHAR(10)   NOT NULL,  -- 'DEBIT' or 'CREDIT'
    amount         NUMERIC(19,4) NOT NULL,
    balance_before NUMERIC(19,4) NOT NULL,
    balance_after  NUMERIC(19,4) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ledger_entries      PRIMARY KEY (id),
    CONSTRAINT fk_ledger_payment      FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_ledger_wallet       FOREIGN KEY (wallet_id)  REFERENCES wallets(user_id),
    CONSTRAINT chk_ledger_entry_type  CHECK (entry_type IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_payment_id ON ledger_entries(payment_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_wallet_id  ON ledger_entries(wallet_id);
CREATE INDEX IF NOT EXISTS idx_payments_sender_id        ON payments(sender_id);
CREATE INDEX IF NOT EXISTS idx_payments_receiver_id      ON payments(receiver_id);
CREATE INDEX IF NOT EXISTS idx_payments_status           ON payments(status);

-- Seed wallets
INSERT INTO wallets (user_id, balance, currency) VALUES
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 10000.0000, 'USD'),
    ('b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22', 5000.0000,  'USD'),
    ('c2ccde77-2e2d-4cf0-dd8f-8dd1df502c33', 2500.0000,  'USD'),
    ('d3ddef66-3f3e-4de1-ae9a-9ee2ea613d44', 500.0000,   'USD')
ON CONFLICT (user_id) DO NOTHING;
