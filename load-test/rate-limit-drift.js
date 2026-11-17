// Measures how far admitted traffic drifts from the configured rate limits when three
// gateway instances sit behind nginx (design note 24). Run once per limiter backend:
//
//   RATE_LIMIT_BACKEND=redis     docker compose up -d   ->  k6 run ... --out json=drift.json
//   RATE_LIMIT_BACKEND=in-memory docker compose up -d   ->  (same)
//   python3 load-test/drift_report.py drift.json
//
// Two demo clients (application-local.yml), each with one limit that binds:
//   drift-requests:   300 requests / minute   (token limit huge)
//   drift-tokens:   3,000 tokens   / minute   (request limit huge)
//
// Each client is offered RATE requests per second (default 25 = 1,500 per minute, five
// times the request limit). The offer must exceed the worst case: if per-instance limiting
// admits up to 3x the limit, offering only 3x would hide the effect.
//
// Every admitted request is recorded with its time (and its actual tokens from `usage`),
// so the report can compute the most admitted in ANY 60-second window. That is what
// "300 per minute" promises.

import http from 'k6/http';
import { Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '3m';
const RATE = parseInt(__ENV.RATE || '25', 10);

const admittedRequests = new Counter('admitted_requests');
const admittedTokens = new Counter('admitted_tokens');
const rejectedRequests = new Counter('rejected_requests');
const unexpectedResponses = new Counter('unexpected_responses');

function scenario(exec) {
  return {
    executor: 'constant-arrival-rate', // a fixed offered rate, whatever the responses do
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: 40,
    maxVUs: 300,
    exec,
  };
}

export const options = {
  scenarios: {
    requests: scenario('requestLimited'),
    tokens: scenario('tokenLimited'),
  },
  // Anything other than 200 or 429 means the run measured something else: fail it.
  thresholds: { unexpected_responses: ['count==0'] },
};

function send(apiKey, client, maxTokens) {
  const body = JSON.stringify({
    model: 'mock-gpt',
    max_tokens: maxTokens,
    messages: [{ role: 'user', content: 'Say hello' }],
  });
  const res = http.post(`${GATEWAY}/v1/chat/completions`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
    tags: { client },
  });
  if (res.status === 200) {
    admittedRequests.add(1, { client });
    admittedTokens.add(res.json('usage.total_tokens'), { client });
  } else if (res.status === 429) {
    rejectedRequests.add(1, { client });
  } else {
    unexpectedResponses.add(1, { client, status: String(res.status) });
  }
}

export function requestLimited() {
  send('drift-requests-key', 'requests', 50);
}

export function tokenLimited() {
  send('drift-tokens-key', 'tokens', 100);
}
