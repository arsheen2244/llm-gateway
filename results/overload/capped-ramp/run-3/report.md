| Rate (req/s) | Achieved | Failed | Shed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate | Peak in flight | Peak heap | Restarts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 99.8 | 0 of 6001 | 0 | 0 | +2.4 ms | +5.0 ms | +6.2 ms | 107.9 ms | 101.7 ms | 0 | 0.1 s | - | 6 | 77 of 384 MB | 0 |
| 200 | 199.7 | 0 of 12001 | 0 | 0 | +1.7 ms | +4.7 ms | +7.9 ms | 109.5 ms | 101.6 ms | 0 | 0.1 s | - | 11 | 78 of 384 MB | 0 |
| 400 | 399.3 | 0 of 24001 | 0 | 0 | +1.4 ms | +7.3 ms | +15.0 ms | 116.6 ms | 101.6 ms | 0 | 0.1 s | - | 22 | 76 of 384 MB | 0 |
| 600 | 599.0 | 0 of 36001 | 0 | 0 | +1.6 ms | +6.1 ms | +11.9 ms | 113.4 ms | 101.5 ms | 0 | 0.1 s | - | 32 | 76 of 384 MB | 0 |
| 800 | 798.6 | 0 of 48001 | 0 | 0 | +2.3 ms | +7.8 ms | +14.0 ms | 115.7 ms | 101.6 ms | 0 | 0.1 s | - | 39 | 78 of 384 MB | 0 |
| 1000 | 998.3 | 0 of 60001 | 0 | 0 | +3.9 ms | +14.9 ms | +27.3 ms | 129.1 ms | 101.8 ms | 2188 | 2.0 s | 1094/s | 60 | 85 of 384 MB | 0 |
| 1200 | 1197.9 | 0 of 72001 | 0 | 0 | +5.5 ms | +23.4 ms | +53.9 ms | 155.7 ms | 101.8 ms | 21166 | 10.7 s | 1978/s | 89 | 133 of 384 MB | 0 |
| 1600 | 1596.9 | 0 of 96002 | 0 | 0 | +14.0 ms | +59.7 ms | +100.0 ms | 202.0 ms | 102.0 ms | 57192 | 28.4 s | 2014/s | 154 | 157 of 384 MB | 0 |
| 2000 | 1994.9 | 0 of 119913 | 0 | 89 | n/a | n/a | n/a | 268.8 ms | 101.9 ms | 83209 | 40.7 s | 2044/s | 222 | 197 of 384 MB | 0 |

Highest rate sustained (no errors, no dropped requests): 1600/s
::notice title=full-stack load test::| 100 | 99.8 | 0 of 6001 | 0 | 0 | +2.4 ms | +5.0 ms | +6.2 ms | 107.9 ms | 101.7 ms | 0 | 0.1 s | - | 6 | 77 of 384 MB | 0 |
::notice title=full-stack load test::| 200 | 199.7 | 0 of 12001 | 0 | 0 | +1.7 ms | +4.7 ms | +7.9 ms | 109.5 ms | 101.6 ms | 0 | 0.1 s | - | 11 | 78 of 384 MB | 0 |
::notice title=full-stack load test::| 400 | 399.3 | 0 of 24001 | 0 | 0 | +1.4 ms | +7.3 ms | +15.0 ms | 116.6 ms | 101.6 ms | 0 | 0.1 s | - | 22 | 76 of 384 MB | 0 |
::notice title=full-stack load test::| 600 | 599.0 | 0 of 36001 | 0 | 0 | +1.6 ms | +6.1 ms | +11.9 ms | 113.4 ms | 101.5 ms | 0 | 0.1 s | - | 32 | 76 of 384 MB | 0 |
::notice title=full-stack load test::| 800 | 798.6 | 0 of 48001 | 0 | 0 | +2.3 ms | +7.8 ms | +14.0 ms | 115.7 ms | 101.6 ms | 0 | 0.1 s | - | 39 | 78 of 384 MB | 0 |
::notice title=full-stack load test::| 1000 | 998.3 | 0 of 60001 | 0 | 0 | +3.9 ms | +14.9 ms | +27.3 ms | 129.1 ms | 101.8 ms | 2188 | 2.0 s | 1094/s | 60 | 85 of 384 MB | 0 |
::notice title=full-stack load test::| 1200 | 1197.9 | 0 of 72001 | 0 | 0 | +5.5 ms | +23.4 ms | +53.9 ms | 155.7 ms | 101.8 ms | 21166 | 10.7 s | 1978/s | 89 | 133 of 384 MB | 0 |
::notice title=full-stack load test::| 1600 | 1596.9 | 0 of 96002 | 0 | 0 | +14.0 ms | +59.7 ms | +100.0 ms | 202.0 ms | 102.0 ms | 57192 | 28.4 s | 2014/s | 154 | 157 of 384 MB | 0 |
::notice title=full-stack load test::| 2000 | 1994.9 | 0 of 119913 | 0 | 89 | n/a | n/a | n/a | 268.8 ms | 101.9 ms | 83209 | 40.7 s | 2044/s | 222 | 197 of 384 MB | 0 |
::notice title=full-stack load test::
::notice title=full-stack load test::Highest rate sustained (no errors, no dropped requests): 1600/s
