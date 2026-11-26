| Rate (req/s) | Achieved | Failed | Shed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate | Peak in flight | Peak heap | Restarts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1000 | 541.0 | 95 of 33863 | 0 | 26138 | n/a | n/a | n/a | 14775.4 ms | 102.5 ms | 12976 | 11.9 s | 1090/s | 1985 | 366 of 384 MB | 0 |
| 1200 | 440.2 | 1697 of 34675 | 0 | 37326 | n/a | n/a | n/a | 28890.8 ms | 102.5 ms | -1289 | 0.1 s | - | 2395 | 375 of 384 MB | 0 (1 unreachable) |
| 1600 | 852.4 | 3202 of 53949 | 0 | 42050 | n/a | n/a | n/a | 5735.6 ms | 102.8 ms | 24704 | 25.5 s | 969/s | 2395 | 362 of 384 MB | 0 (1 unreachable) |
| 2000 | 562.4 | 11644 of 36145 | 0 | 83856 | n/a | n/a | n/a | 6918.3 ms | 103.7 ms | -2 | 0.3 s | - | 1875 | 334 of 384 MB | 2 (3 unreachable) |

No rate was sustained.
::notice title=full-stack load test::| 1000 | 541.0 | 95 of 33863 | 0 | 26138 | n/a | n/a | n/a | 14775.4 ms | 102.5 ms | 12976 | 11.9 s | 1090/s | 1985 | 366 of 384 MB | 0 |
::notice title=full-stack load test::| 1200 | 440.2 | 1697 of 34675 | 0 | 37326 | n/a | n/a | n/a | 28890.8 ms | 102.5 ms | -1289 | 0.1 s | - | 2395 | 375 of 384 MB | 0 (1 unreachable) |
::notice title=full-stack load test::| 1600 | 852.4 | 3202 of 53949 | 0 | 42050 | n/a | n/a | n/a | 5735.6 ms | 102.8 ms | 24704 | 25.5 s | 969/s | 2395 | 362 of 384 MB | 0 (1 unreachable) |
::notice title=full-stack load test::| 2000 | 562.4 | 11644 of 36145 | 0 | 83856 | n/a | n/a | n/a | 6918.3 ms | 103.7 ms | -2 | 0.3 s | - | 1875 | 334 of 384 MB | 2 (3 unreachable) |
::notice title=full-stack load test::
::notice title=full-stack load test::No rate was sustained.
