# Load tests

## gateway-overhead.js

Measures the latency the gateway adds on top of the provider: identical requests go
straight to the mock and through the gateway, and the script prints p50/p95 for each
plus the difference.

1. Start the mock with fixed latency and no errors, and the gateway pointing at it.
   With Docker:

   ```bash
   MOCK_LATENCY_MS=50 MOCK_CHUNK_COUNT=10 MOCK_CHUNK_DELAY_MS=5 docker compose up --build
   ```

   Or without Docker, from the repo root after `./mvnw package`:

   ```bash
   java -jar mock-provider/target/mock-provider-0.1.0-SNAPSHOT-exec.jar --mock.latency-ms=50 --mock.chunk-count=10 --mock.chunk-delay-ms=5
   java -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar
   ```

2. Run k6:

   ```bash
   k6 run load-test/gateway-overhead.js
   ```

   Options: `VUS` (default 10), `PHASE_SECONDS` (default 30), `GATEWAY_URL`, `MOCK_URL`.

**How to read the result:** phases run one after another after an unrecorded warm-up,
so the JIT compiler has optimised the hot code and the targets don't compete for CPU.
"p95 overhead" is p95(gateway) minus p95(direct), which is a difference of
percentiles, not a per-request percentile. k6, the gateway and the mock all share one
machine, so treat results as indicative, and note the hardware with any numbers you
publish. The run is invalid if the error rate is above 0, because the threshold fails.
