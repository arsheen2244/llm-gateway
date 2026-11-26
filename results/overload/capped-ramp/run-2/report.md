| Rate (req/s) | Achieved | Failed | Shed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate | Peak in flight | Peak heap | Restarts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 99.8 | 0 of 6001 | 0 | 0 | +3.8 ms | +8.6 ms | +12.8 ms | 115.5 ms | 102.7 ms | 0 | 0.1 s | - | 15 | 87 of 384 MB | 0 |
| 200 | 199.7 | 0 of 12001 | 0 | 0 | +4.3 ms | +11.8 ms | +19.7 ms | 122.3 ms | 102.6 ms | 0 | 0.1 s | - | 13 | 88 of 384 MB | 0 |
| 400 | 399.3 | 0 of 24000 | 0 | 0 | +6.2 ms | +26.4 ms | +47.0 ms | 149.1 ms | 102.1 ms | 0 | 0.1 s | - | 26 | 84 of 384 MB | 0 |
| 600 | 599.0 | 0 of 36001 | 0 | 0 | +6.5 ms | +26.1 ms | +51.2 ms | 153.4 ms | 102.1 ms | 4008 | 4.4 s | 911/s | 54 | 101 of 384 MB | 0 |
| 800 | 798.6 | 0 of 48001 | 0 | 0 | +11.2 ms | +42.7 ms | +69.7 ms | 171.8 ms | 102.1 ms | 21706 | 22.7 s | 956/s | 59 | 105 of 384 MB | 0 |
| 1000 | 925.9 | 3920 of 55709 | 3920 | 4291 | n/a | n/a | n/a | 3856.7 ms | 102.4 ms | 33545 | 35.6 s | 942/s | 500 | 333 of 384 MB | 0 |
| 1200 | 1150.4 | 0 of 69404 | 0 | 2595 | n/a | n/a | n/a | 669.3 ms | 103.0 ms | 51316 | 54.4 s | 943/s | 500 | 215 of 384 MB | 0 |
| 1600 | 1213.1 | 939 of 73156 | 939 | 22501 | n/a | n/a | n/a | 1917.5 ms | 103.5 ms | 55024 | 58.5 s | 941/s | 500 | 293 of 384 MB | 0 |
| 2000 | 1217.1 | 451 of 73859 | 451 | 46143 | n/a | n/a | n/a | 1557.3 ms | 109.0 ms | 56302 | 60.0 s | 938/s | 500 | 272 of 384 MB | 0 |

Highest rate sustained (no errors, no dropped requests): 800/s
::notice title=full-stack load test::| 100 | 99.8 | 0 of 6001 | 0 | 0 | +3.8 ms | +8.6 ms | +12.8 ms | 115.5 ms | 102.7 ms | 0 | 0.1 s | - | 15 | 87 of 384 MB | 0 |
::notice title=full-stack load test::| 200 | 199.7 | 0 of 12001 | 0 | 0 | +4.3 ms | +11.8 ms | +19.7 ms | 122.3 ms | 102.6 ms | 0 | 0.1 s | - | 13 | 88 of 384 MB | 0 |
::notice title=full-stack load test::| 400 | 399.3 | 0 of 24000 | 0 | 0 | +6.2 ms | +26.4 ms | +47.0 ms | 149.1 ms | 102.1 ms | 0 | 0.1 s | - | 26 | 84 of 384 MB | 0 |
::notice title=full-stack load test::| 600 | 599.0 | 0 of 36001 | 0 | 0 | +6.5 ms | +26.1 ms | +51.2 ms | 153.4 ms | 102.1 ms | 4008 | 4.4 s | 911/s | 54 | 101 of 384 MB | 0 |
::notice title=full-stack load test::| 800 | 798.6 | 0 of 48001 | 0 | 0 | +11.2 ms | +42.7 ms | +69.7 ms | 171.8 ms | 102.1 ms | 21706 | 22.7 s | 956/s | 59 | 105 of 384 MB | 0 |
::notice title=full-stack load test::| 1000 | 925.9 | 3920 of 55709 | 3920 | 4291 | n/a | n/a | n/a | 3856.7 ms | 102.4 ms | 33545 | 35.6 s | 942/s | 500 | 333 of 384 MB | 0 |
::notice title=full-stack load test::| 1200 | 1150.4 | 0 of 69404 | 0 | 2595 | n/a | n/a | n/a | 669.3 ms | 103.0 ms | 51316 | 54.4 s | 943/s | 500 | 215 of 384 MB | 0 |
::notice title=full-stack load test::| 1600 | 1213.1 | 939 of 73156 | 939 | 22501 | n/a | n/a | n/a | 1917.5 ms | 103.5 ms | 55024 | 58.5 s | 941/s | 500 | 293 of 384 MB | 0 |
::notice title=full-stack load test::| 2000 | 1217.1 | 451 of 73859 | 451 | 46143 | n/a | n/a | n/a | 1557.3 ms | 109.0 ms | 56302 | 60.0 s | 938/s | 500 | 272 of 384 MB | 0 |
::notice title=full-stack load test::
::notice title=full-stack load test::Highest rate sustained (no errors, no dropped requests): 800/s
