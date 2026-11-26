| Rate (req/s) | Achieved | Failed | Shed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate | Peak in flight | Peak heap | Restarts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1000 | 625.6 | 0 of 38560 | 0 | 21441 | n/a | n/a | n/a | 13368.0 ms | 102.0 ms | 18945 | 16.2 s | 1169/s | 1998 | 376 of 384 MB | 0 |
| 1200 | 1183.7 | 0 of 71174 | 0 | 827 | n/a | n/a | n/a | 580.8 ms | 101.9 ms | 53445 | 45.0 s | 1188/s | 1998 | 316 of 384 MB | 0 |
| 1600 | 1589.5 | 0 of 95606 | 0 | 397 | n/a | n/a | n/a | 337.7 ms | 102.2 ms | 68986 | 56.9 s | 1212/s | 307 | 166 of 384 MB | 0 |
| 2000 | 1078.0 | 4315 of 86868 | 0 | 33133 | n/a | n/a | n/a | 24116.8 ms | 102.5 ms | 46148 | 37.2 s | 1241/s | 2744 | 378 of 384 MB | 1 (1 unreachable) |

No rate was sustained.
::notice title=full-stack load test::| 1000 | 625.6 | 0 of 38560 | 0 | 21441 | n/a | n/a | n/a | 13368.0 ms | 102.0 ms | 18945 | 16.2 s | 1169/s | 1998 | 376 of 384 MB | 0 |
::notice title=full-stack load test::| 1200 | 1183.7 | 0 of 71174 | 0 | 827 | n/a | n/a | n/a | 580.8 ms | 101.9 ms | 53445 | 45.0 s | 1188/s | 1998 | 316 of 384 MB | 0 |
::notice title=full-stack load test::| 1600 | 1589.5 | 0 of 95606 | 0 | 397 | n/a | n/a | n/a | 337.7 ms | 102.2 ms | 68986 | 56.9 s | 1212/s | 307 | 166 of 384 MB | 0 |
::notice title=full-stack load test::| 2000 | 1078.0 | 4315 of 86868 | 0 | 33133 | n/a | n/a | n/a | 24116.8 ms | 102.5 ms | 46148 | 37.2 s | 1241/s | 2744 | 378 of 384 MB | 1 (1 unreachable) |
::notice title=full-stack load test::
::notice title=full-stack load test::No rate was sustained.
