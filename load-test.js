import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';

const USERS = [
  'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
  'b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22',
  'c2ccde77-2e2d-4cf0-dd8f-8dd1df502c33'
];

export const options = {
  vus: 10,
  duration: '30s',
};

function generateKey() {
  return Math.random().toString(36).substring(2, 12);
}

export default function () {

  let sender = USERS[Math.floor(Math.random() * USERS.length)];
  let receiver;

  do {
    receiver = USERS[Math.floor(Math.random() * USERS.length)];
  } while (receiver === sender);

  const payload = JSON.stringify({
    idempotencyKey: generateKey(),   // 🔥 REQUIRED
    senderId: sender,
    receiverId: receiver,
    amount: 1,
    currency: 'USD'
  });

  const res = http.post(`${BASE_URL}/v1/payments`, payload, {
    headers: { 'Content-Type': 'application/json' }
  });

  //console.log(`STATUS: ${res.status}`);

  check(res, {
    'status is success': (r) => r.status === 200 || r.status === 202,
  });

  sleep(0.1);
}