# Full-stack load test results

Measured by `.github/workflows/full-stack-load.yml`, run 45755495967 (commit affa7c4), on GitHub
Actions runners: 4 vCPUs (AMD EPYC 7763), 15 GB, everything on the one machine (k6, nginx, three
gateways, Redis, Kafka, PostgreSQL, the usage consumer, Prometheus, Grafana, the mock).

- `run-2/`, `run-3/`: two independent repetitions, each on its own runner. `report.md` is the table,
  `machine.txt` the hardware, the JSON files k6's and the consumer check's raw numbers.
- Run 1 of the same workflow run has no numbers: its smoke test got a 502 from nginx while one
  gateway was still starting, so the measurement never started (the smoke test now waits for
  all three).

How to read them, and what they don't show: design note 43.
