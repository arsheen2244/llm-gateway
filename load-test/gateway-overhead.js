// Measures the latency the gateway adds on top of the provider.
//
// The same request is sent (a) straight to the mock provider and (b) through the
// gateway to that mock. With the mock's own latency fixed and errors off, the
// difference between (b) and (a) is the gateway's overhead plus one extra local hop.
//
// Phases run one after another (not at the same time) so they don't compete for CPU:
//   warmup (both targets, not recorded) -> direct JSON -> gateway JSON
//   -> direct stream -> gateway stream
//
// Run (both apps must be up; the mock with fixed latency and no errors):
//   k6 run load-test/gateway-overhead.js
// Options (env vars): GATEWAY_URL, MOCK_URL, VUS (default 10), PHASE_SECONDS (default 30)
//
// Note: "p95 overhead" is reported as p95(gateway) - p95(direct). That is a
// difference of percentiles, not the 95th percentile of per-request overhead.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const MOCK = __ENV.MOCK_URL || 'http://localhost:8081';
const VUS = parseInt(__ENV.VUS || '10', 10);
const PHASE = parseInt(__ENV.PHASE_SECONDS || '30', 10);
const WARMUP = 15;
const GAP = 2;

const PATH = '/v1/chat/completions';
// The mock accepts any bearer token. The gateway needs a real client key: the
// "load-test" demo client (local profile) has limits high enough not to throttle.
const MOCK_HEADERS = { 'Content-Type': 'application/json', Authorization: 'Bearer mock-key' };
const GATEWAY_HEADERS = {
  'Content-Type': 'application/json',
  Authorization: `Bearer ${__ENV.GATEWAY_KEY || 'load-test-key'}`,
};
const JSON_BODY = JSON.stringify({ model: 'mock-gpt', messages: [{ role: 'user', content: 'Say hello' }] });
const STREAM_BODY = JSON.stringify({ model: 'mock-gpt', stream: true, messages: [{ role: 'user', content: 'Say hello' }] });

// "true" = these trends hold times, so k6 prints them in ms.
const total = {
  direct_json: new Trend('total_direct_json', true),
  gateway_json: new Trend('total_gateway_json', true),
  direct_stream: new Trend('total_direct_stream', true),
  gateway_stream: new Trend('total_gateway_stream', true),
};
const ttfb = {
  direct_stream: new Trend('ttfb_direct_stream', true),
  gateway_stream: new Trend('ttfb_gateway_stream', true),
};
const errors = new Rate('errors');

function phase(exec, startSeconds) {
  return { executor: 'constant-vus', vus: VUS, duration: `${PHASE}s`, exec, startTime: `${startSeconds}s` };
}

const t0 = WARMUP + GAP;
const step = PHASE + GAP;

export const options = {
  scenarios: {
    warmup: { executor: 'constant-vus', vus: VUS, duration: `${WARMUP}s`, exec: 'warmup' },
    direct_json: phase('directJson', t0),
    gateway_json: phase('gatewayJson', t0 + step),
    direct_stream: phase('directStream', t0 + 2 * step),
    gateway_stream: phase('gatewayStream', t0 + 3 * step),
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
  thresholds: { errors: ['rate<0.01'] },
};

function send(baseUrl, body, isStream) {
  const headers = baseUrl === GATEWAY ? GATEWAY_HEADERS : MOCK_HEADERS;
  const res = http.post(baseUrl + PATH, body, { headers });
  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    // r.body is null when the connection failed, so default to '' (counts as an error).
    'complete body': (r) => (isStream ? (r.body || '').includes('data: [DONE]') : (r.body || '').includes('"chat.completion"')),
  });
  errors.add(!ok);
  return res;
}

export function warmup() {
  send(MOCK, JSON_BODY, false);
  send(GATEWAY, JSON_BODY, false);
  send(MOCK, STREAM_BODY, true);
  send(GATEWAY, STREAM_BODY, true);
}

export function directJson() {
  total.direct_json.add(send(MOCK, JSON_BODY, false).timings.duration);
}

export function gatewayJson() {
  total.gateway_json.add(send(GATEWAY, JSON_BODY, false).timings.duration);
}

export function directStream() {
  const res = send(MOCK, STREAM_BODY, true);
  total.direct_stream.add(res.timings.duration);
  ttfb.direct_stream.add(res.timings.waiting);
}

export function gatewayStream() {
  const res = send(GATEWAY, STREAM_BODY, true);
  total.gateway_stream.add(res.timings.duration);
  ttfb.gateway_stream.add(res.timings.waiting);
}

// ---- summary: a small comparison table instead of k6's long default output ----

function stat(data, metric, key) {
  const m = data.metrics[metric];
  return m ? m.values[key] : NaN;
}

function row(data, label, directMetric, gatewayMetric) {
  const cells = [];
  for (const key of ['med', 'p(95)']) {
    const d = stat(data, directMetric, key);
    const g = stat(data, gatewayMetric, key);
    cells.push({ key, direct: d, gateway: g, overhead: g - d });
  }
  return { label, cells, count: data.metrics[gatewayMetric] ? data.metrics[gatewayMetric].values.count : 0 };
}

const fmt = (n) => (Number.isFinite(n) ? n.toFixed(2).padStart(8) : '     n/a');

export function handleSummary(data) {
  const rows = [
    row(data, 'non-streaming, total', 'total_direct_json', 'total_gateway_json'),
    row(data, 'streaming, time to first byte', 'ttfb_direct_stream', 'ttfb_gateway_stream'),
    row(data, 'streaming, total', 'total_direct_stream', 'total_gateway_stream'),
  ];
  const lines = [
    '',
    `Gateway overhead (ms), ${VUS} VUs, ${PHASE}s per phase`,
    'measurement                       |   p50 direct  gateway overhead |   p95 direct  gateway overhead',
    '----------------------------------+--------------------------------+-------------------------------',
  ];
  for (const r of rows) {
    const [p50, p95] = r.cells;
    lines.push(
      `${r.label.padEnd(34)}| ${fmt(p50.direct)} ${fmt(p50.gateway)} ${fmt(p50.overhead)} | ${fmt(p95.direct)} ${fmt(p95.gateway)} ${fmt(p95.overhead)}`,
    );
  }
  const errorRate = data.metrics.errors ? data.metrics.errors.values.rate : NaN;
  lines.push('', `error rate: ${(errorRate * 100).toFixed(2)}%`, '');

  return {
    stdout: lines.join('\n'),
    'load-test/results/summary.json': JSON.stringify({ vus: VUS, phaseSeconds: PHASE, rows, errorRate }, null, 2),
  };
}
