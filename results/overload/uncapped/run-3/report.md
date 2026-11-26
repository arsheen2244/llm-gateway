| Rate (req/s) | Achieved | Failed | Shed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate | Peak in flight | Peak heap | Restarts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1000 | 736.0 | 86 of 45392 | 0 | 14609 | n/a | n/a | n/a | 11823.1 ms | 101.9 ms | 9191 | 5.0 s | 1838/s | 1991 | 379 of 384 MB | 0 |
| 1200 | 1197.9 | 0 of 72000 | 0 | 0 | +8.4 ms | +51.3 ms | +95.9 ms | 197.8 ms | 101.9 ms | 26782 | 13.0 s | 2060/s | 1991 | 324 of 384 MB | 0 |
| 1600 | 1597.2 | 0 of 96001 | 0 | 0 | +12.6 ms | +47.2 ms | +78.8 ms | 180.7 ms | 101.9 ms | 54538 | 27.0 s | 2020/s | 138 | 162 of 384 MB | 0 |
| 2000 | 1961.8 | 0 of 118157 | 0 | 1845 | n/a | n/a | n/a | 895.2 ms | 102.2 ms | 82236 | 40.3 s | 2041/s | 669 | 266 of 384 MB | 0 |

Highest rate sustained (no errors, no dropped requests): 1600/s
::notice title=full-stack load test::| 1000 | 736.0 | 86 of 45392 | 0 | 14609 | n/a | n/a | n/a | 11823.1 ms | 101.9 ms | 9191 | 5.0 s | 1838/s | 1991 | 379 of 384 MB | 0 |
::notice title=full-stack load test::| 1200 | 1197.9 | 0 of 72000 | 0 | 0 | +8.4 ms | +51.3 ms | +95.9 ms | 197.8 ms | 101.9 ms | 26782 | 13.0 s | 2060/s | 1991 | 324 of 384 MB | 0 |
::notice title=full-stack load test::| 1600 | 1597.2 | 0 of 96001 | 0 | 0 | +12.6 ms | +47.2 ms | +78.8 ms | 180.7 ms | 101.9 ms | 54538 | 27.0 s | 2020/s | 138 | 162 of 384 MB | 0 |
::notice title=full-stack load test::| 2000 | 1961.8 | 0 of 118157 | 0 | 1845 | n/a | n/a | n/a | 895.2 ms | 102.2 ms | 82236 | 40.3 s | 2041/s | 669 | 266 of 384 MB | 0 |
::notice title=full-stack load test::
::notice title=full-stack load test::Highest rate sustained (no errors, no dropped requests): 1600/s
