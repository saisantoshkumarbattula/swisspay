-- Seed wallets required by PaymentGatewayIntegrationTest.
-- Uses UPSERT so re-runs before each test method are safe.
INSERT INTO wallets (user_id, balance, currency, version) VALUES
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 10000.0000, 'USD', 0),
    ('b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22',  5000.0000, 'USD', 0)
ON CONFLICT (user_id) DO UPDATE
    SET balance  = EXCLUDED.balance,
        version  = EXCLUDED.version;
