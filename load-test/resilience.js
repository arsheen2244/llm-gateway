// Week-3 resilience measurements (design note 31). One phase per run:
//
//   PHASE=failover  model routed to [mock-down, mock-backup]: success rate during a full
//                   outage of the primary
//   PHASE=single    model routed to [mock-down] only: the same outage without failover
//   PHASE=slow      model routed to mock-slow (3% of requests +2 s): latency percentiles;
//                   run once with hedging on and once with it off
//
// Needs docker-compose.yml + docker-compose.resilience.yml. Extra request volume (retries,
// hedges) is measured at the mocks' /stats by the workflow, before and after each phase.
//
//   k6 run -e PHASE=failover load-test/resilience.js

import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const PHASE = __ENV.PHASE || 'failover';
const RATE = parseInt(__ENV.RATE || '20', 10);
const DURATION = __ENV.DURATION || (PHASE === 'slow' ? '180s' : '90s');
const MODEL = { failover: 'outage-failover-gpt', single: 'outage-single-gpt', slow: 'slow-gpt' }[PHASE];

// Only successful requests' latencies: "how long does a client wait for an answer".
const okLatency = new Trend('ok_latency', true);
const okRequests = new Counter('ok_requests');
const failedRequests = new Counter('failed_requests');

export const options = {
  scenarios: {
    main: {
      executor: 'constant-arrival-rate', // a fixed offered load, whatever the gateway does
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const body = JSON.stringify({ model: MODEL, messages: [{ role: 'user', content: 'Say hello' }] });
  const res = http.post(`${GATEWAY}/v1/chat/completions`, body, {
    // The "load-test" demo client has limits high enough never to be throttled.
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer load-test-key' },
    timeout: '60s',
  });
  if (res.status === 200) {
    okRequests.add(1);
    okLatency.add(res.timings.duration);
  } else {
    failedRequests.add(1, { status: String(res.status) });
  }
}

export function handleSummary(data) {
  const value = (metric, key) => (data.metrics[metric] ? data.metrics[metric].values[key] : 0);
  const ok = value('ok_requests', 'count');
  const failed = value('failed_requests', 'count');
  const result = {
    phase: PHASE,
    rate: RATE,
    duration: DURATION,
    requests: ok + failed,
    ok,
    failed,
    successRate: ok + failed > 0 ? ok / (ok + failed) : 0,
    p50: value('ok_latency', 'med'),
    p95: value('ok_latency', 'p(95)'),
    p99: value('ok_latency', 'p(99)'),
    max: value('ok_latency', 'max'),
  };
  return {
    stdout: JSON.stringify(result, null, 2) + '\n',
    [`resilience-${PHASE}.json`]: JSON.stringify(result),
  };
}
