import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ─────────────────────────────────────────────────────────────────────────────
// SwiftPay Load Test — 250 TPS sustained for ~4000s (1M transactions)
// Run: k6 run load-test.js
// ─────────────────────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Custom metrics
const successCount  = new Counter('payment_success');
const failureCount  = new Counter('payment_failure');
const duplicateRate = new Rate('payment_duplicate');
const latency       = new Trend('payment_latency_ms', true);

export const options = {
  scenarios: {
    sustained_load: {
      executor: 'constant-arrival-rate',
      rate: 250,                // 250 iterations/second
      timeUnit: '1s',
      duration: '4000s',        // ~1M total requests
      preAllocatedVUs: 300,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_duration:  ['p(95)<500', 'p(99)<1000'],  // 95th p < 500ms
    http_req_failed:    ['rate<0.01'],                  // < 1% errors
    payment_duplicate:  ['rate<0.001'],                 // < 0.1% duplicate keys
  },
};

// Wallet IDs seeded in V1__init_schema.sql
const SENDERS = [
  'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
  'b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22',
  'c2ccde77-2e2d-4cf0-dd8f-8dd1df502c33',
];
const RECEIVERS = [
  'b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22',
  'c2ccde77-2e2d-4cf0-dd8f-8dd1df502c33',
  'd3ddef66-3f3e-4de1-ae9a-9ee2ea613d44',
];

function randomElement(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

export default function () {
  const payload = JSON.stringify({
    idempotencyKey: generateUUID(),
    senderId:       randomElement(SENDERS),
    receiverId:     randomElement(RECEIVERS),
    amount:         (Math.random() * 9 + 1).toFixed(2),  // $1–$10
    currency:       'USD',
  });

  const headers = { 'Content-Type': 'application/json' };
  const start = Date.now();
  const res = http.post(`${BASE_URL}/v1/payments`, payload, { headers, timeout: '5s' });
  latency.add(Date.now() - start);

  const ok = check(res, {
    'status is 202': (r) => r.status === 202,
    'has transactionId': (r) => {
      try { return JSON.parse(r.body).transactionId !== undefined; } catch { return false; }
    },
  });

  if (res.status === 202) {
    successCount.add(1);
  } else if (res.status === 409) {
    duplicateRate.add(1);
    failureCount.add(0);
  } else {
    failureCount.add(1);
  }

  sleep(0); // No sleep — rate controlled by arrival-rate executor
}
