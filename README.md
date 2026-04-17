# SwiftPay — Real-Time Payment Ledger

> Hackathon submission: A production-grade, event-driven P2P payment platform built on Spring Boot 3, Kafka, Redis, and PostgreSQL.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  Client                                                              │
│  POST /v1/payments ──► Transaction Gateway (Service A, :8080)       │
│                         │  1. Redis idempotency check (SET NX EX)   │
│                         │  2. Redis cached balance validation        │
│                         │  3. Save PENDING → PostgreSQL             │
│                         │  4. Emit PaymentInitiated → Kafka         │
│                         ▼                                            │
│             Kafka ("payment-initiated")                              │
│                         │                                            │
│                         ▼                                            │
│            Ledger Service (Service B, :8081)                        │
│                         │  1. PESSIMISTIC_WRITE lock on wallets     │
│                         │  2. Atomic debit/credit                   │
│                         │  3. Double-entry ledger_entries           │
│                         │  4. Emit PaymentCompleted/Failed → Kafka  │
│                         ▼                                            │
│          Kafka ("payment-completed" / "payment-failed")             │
│                         │                                            │
│                         ▼                                            │
│      Gateway consumer updates payment status in PostgreSQL          │
└─────────────────────────────────────────────────────────────────────┘
```

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language  | Java 21 (Spring Boot 3.2) |
| API       | Spring Web MVC + SpringDoc OpenAPI |
| Database  | PostgreSQL 16 (JPA/Hibernate + Flyway) |
| Messaging | Apache Kafka 3.6 (Spring Kafka) |
| Caching   | Redis 7 (Lettuce client) |
| Auth      | N/A (out of scope for hackathon) |
| Container | Docker + docker-compose |
| CI/CD     | GitHub Actions → GHCR |
| Load Test | K6 |

---

## Quick Start

### Prerequisites
- Docker & Docker Compose v2
- JDK 21 (for local dev only)

### Run everything locally

```bash
# Clone repo
git clone https://github.com/your-org/swiftpay.git
cd swiftpay

# Start the entire ecosystem (Postgres + Redis + Kafka + both services)
docker compose up --build

# Optional: Kafka UI
docker compose --profile tools up
```

Services:
| Service | URL |
|---------|-----|
| Transaction Gateway API | http://localhost:8080 |
| Ledger Service API      | http://localhost:8081 |
| Swagger UI (Gateway)    | http://localhost:8080/swagger-ui.html |
| Swagger UI (Ledger)     | http://localhost:8081/swagger-ui.html |
| Kafka UI                | http://localhost:8090 (profile: tools) |
| Health (Gateway)        | http://localhost:8080/actuator/health |
| Health (Ledger)         | http://localhost:8081/actuator/health |

---

## API Reference

### POST /v1/payments

Initiate a P2P payment.

```json
{
  "idempotencyKey": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "senderId":       "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
  "receiverId":     "b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22",
  "amount":         "250.00",
  "currency":       "USD"
}
```

Response codes:
- `202 Accepted` — Payment accepted, PENDING status
- `400 Bad Request` — Validation errors
- `404 Not Found` — Sender wallet not found
- `409 Conflict` — Duplicate idempotency key
- `422 Unprocessable Entity` — Insufficient funds

### GET /v1/payments/{transactionId}

Poll payment status (PENDING → PROCESSING → COMPLETED | FAILED).

### GET /v1/ledger/transactions/{userId}?page=0&size=20

Paginated transaction history for a user (sent + received).

### GET /v1/ledger/balance/{userId}

Current wallet balance for a user.

---

## Seed Wallets (Test Data)

| User ID | Balance |
|---------|---------|
| `a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11` | $10,000 |
| `b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22` | $5,000  |
| `c2ggde77-2e2d-6gf0-dd8f-8dd1df502c33` | $2,500  |
| `d3hhef66-3f3e-7hg1-ee9g-9ee2eg613d44` | $500    |

---

## Key Design Decisions

### Idempotency (Redis SET NX EX)
- Every payment request carries a client-supplied `idempotencyKey`
- Gateway uses atomic `SET NX EX` (24h TTL) — if the key exists, the existing payment is returned with **409 Conflict**
- This prevents double-charges from network retries

### Balance Validation (Redis Cache)
- Gateway caches user balances in Redis as a fast pre-check
- Ledger performs a **final, authoritative** check after acquiring `PESSIMISTIC_WRITE` locks
- Cache is evicted on every successful transfer by the result consumer

### Atomic Debit/Credit (Pessimistic Locking)
- Both wallets are locked in **consistent key order** (UUID compare) to prevent deadlocks
- Debit and credit execute inside a single `@Transactional` boundary
- Double-entry ledger entries are written for audit

### Kafka Retry & DLT
- Both services use `@RetryableTopic` with exponential back-off
- Gateway: 4 attempts, 1s–10s backoff
- Ledger: 5 attempts, 2s–30s backoff  
- Failed messages land in `payment-initiated-dlt` for manual inspection

### Error Handling
| Scenario | Behavior |
|----------|----------|
| Kafka outage | Consumer retries with exponential back-off; status stays PENDING until Kafka recovers |
| DB constraint violation | Transaction rolls back; FAILED event emitted; idempotency key remains claimable |
| Redis outage | Idempotency guard falls through to DB check (graceful degradation) |
| Duplicate key | Existing record returned immediately with 409 |
| Insufficient funds | 422 returned immediately; no DB record created |

---

## Running Tests

```bash
# Unit tests (no Docker needed)
cd transaction-gateway && ./mvnw test -Dtest="*ServiceTest"
cd ledger-service      && ./mvnw test -Dtest="*ServiceTest"

# Integration tests (Testcontainers — requires Docker)
cd transaction-gateway && ./mvnw test -Dtest="*IntegrationTest"
```

---

## Load Test (K6)

```bash
# Start the stack then run:
k6 run load-test.js

# Target: 250 TPS sustained for 4000s (~1M transactions)
# Thresholds: p95 < 500ms, error rate < 1%
```

---

## CI/CD Pipeline

```
Push to main → GitHub Actions
  ├── Build (Maven)
  ├── Unit Tests (PaymentServiceTest, LedgerServiceTest)
  ├── Integration Tests (Testcontainers)
  ├── Build Docker Images
  └── Push to GHCR (ghcr.io/<owner>/swiftpay-*)
```
