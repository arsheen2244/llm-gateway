// Full-stack load test (design note 43): the Compose stack with every feature on, at one fixed
// request rate, against either the mock directly or the gateways (through nginx).
//
// Open model: requests start at RATE per second whatever the answers do (constant-arrival-rate).
// A closed model (fixed VUs) would slow down with the system and hide how far behind it falls.
// If k6 runs out of VUs, it skips requests and counts them as dropped_iterations: the target
// rate was not sustained.
//
// Every request has a new prompt at temperature 0, so the cache looks it up and stores the
// answer each time (a miss: its most expensive path). Non-streaming only.
//
// Run (stack up): k6 run -e TARGET=gateway -e RATE=200 -e SECONDS=60 -e OUT=result.json load-test/full-stack.js
// TARGET is "direct" (the mock, http://localhost:8081) or "gateway" (nginx, http://localhost:8080).
// load-test/full_stack.sh runs the whole series.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const TARGET = __ENV.TARGET || 'gateway';
const RATE = parseInt(__ENV.RATE || '100', 10);
const SECONDS = parseInt(__ENV.SECONDS || '60', 10);
const URL = TARGET === 'direct'
  ? `${__ENV.MOCK_URL || 'http://localhost:8081'}/v1/chat/completions`
  : `${__ENV.GATEWAY_URL || 'http://localhost:8080'}/v1/chat/completions`;
// The mock accepts any key; the gateway's "load-test" demo client has limits that never bind.
const HEADERS = {
  'Content-Type': 'application/json',
  Authorization: `Bearer ${TARGET === 'direct' ? 'mock-key' : 'load-test-key'}`,
};
const RUN = `${Date.now()}`;

const latency = new Trend('latency_ok', true); // successful requests only
const errors = new Rate('errors');
const answered = new Counter('answered'); // any HTTP status: these reached a gateway
const failed = new Counter('failed');
let logged = 0;

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${SECONDS}s`,
      // About RATE x latency are busy at once; the rest is room for slow moments.
      preAllocatedVUs: Math.max(20, Math.ceil(RATE * 0.3)),
      maxVUs: Math.max(50, RATE * 2),
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max', 'count'],
  discardResponseBodies: false,
};

export default function () {
  const body = JSON.stringify({
    model: 'mock-gpt',
    temperature: 0,
    messages: [{ role: 'user', content: `Load ${RUN} ${__VU} ${__ITER}` }],
  });
  const res = http.post(URL, body, { headers: HEADERS, timeout: '30s' });
  if (res.status !== 0) {
    answered.add(1);
  }
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'complete answer': (r) => (r.body || '').includes('"chat.completion"'),
  });
  errors.add(!ok);
  if (!ok) {
    failed.add(1);
    if (logged < 3) { // say why, a few times per VU
      logged++;
      console.warn(`failed: status ${res.status} ${res.error || ''} ${(res.body || '').slice(0, 200)}`);
    }
  }
  if (ok) {
    latency.add(res.timings.duration);
  }
}

function value(data, metric, key, fallback = 0) {
  const m = data.metrics[metric];
  return m && m.values[key] !== undefined ? m.values[key] : fallback;
}

export function handleSummary(data) {
  const result = {
    target: TARGET,
    rate: RATE,
    seconds: SECONDS,
    requests: value(data, 'http_reqs', 'count'),
    achievedRate: value(data, 'http_reqs', 'rate'),
    answered: value(data, 'answered', 'count'),
    errorRate: value(data, 'errors', 'rate'),
    failed: value(data, 'failed', 'count'),
    droppedIterations: value(data, 'dropped_iterations', 'count'),
    ok: value(data, 'latency_ok', 'count'),
    p50: value(data, 'latency_ok', 'med', NaN),
    p95: value(data, 'latency_ok', 'p(95)', NaN),
    p99: value(data, 'latency_ok', 'p(99)', NaN),
    max: value(data, 'latency_ok', 'max', NaN),
  };
  const line = `${TARGET} at ${RATE}/s: ${result.requests} requests, failed ${result.failed}, `
    + `dropped ${result.droppedIterations}, p50 ${result.p50.toFixed(1)} ms, p95 ${result.p95.toFixed(1)} ms, `
    + `p99 ${result.p99.toFixed(1)} ms\n`;
  return { stdout: line, [__ENV.OUT || 'result.json']: JSON.stringify(result, null, 2) };
}
