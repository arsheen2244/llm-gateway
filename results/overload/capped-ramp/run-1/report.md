| Rate (req/s) | Achieved | Failed | Shed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate | Peak in flight | Peak heap | Restarts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 99.8 | 0 of 6001 | 0 | 0 | +3.7 ms | +7.2 ms | +10.8 ms | 113.2 ms | 102.4 ms | 0 | 0.1 s | - | 9 | 80 of 384 MB | 0 |
| 200 | 199.7 | 0 of 12000 | 0 | 0 | +3.4 ms | +11.1 ms | +20.7 ms | 122.8 ms | 102.1 ms | 0 | 0.2 s | - | 12 | 85 of 384 MB | 0 |
| 400 | 399.3 | 0 of 24001 | 0 | 0 | +5.9 ms | +24.1 ms | +42.4 ms | 144.3 ms | 101.9 ms | 0 | 0.1 s | - | 26 | 85 of 384 MB | 0 |
| 600 | 598.9 | 0 of 36001 | 0 | 0 | +5.6 ms | +24.2 ms | +47.1 ms | 149.0 ms | 101.9 ms | 0 | 0.1 s | - | 41 | 85 of 384 MB | 0 |
| 800 | 798.6 | 0 of 48001 | 0 | 0 | +5.7 ms | +25.4 ms | +50.0 ms | 151.9 ms | 101.9 ms | 5079 | 4.0 s | 1270/s | 51 | 85 of 384 MB | 0 |
| 1000 | 998.1 | 0 of 60002 | 0 | 0 | +7.0 ms | +30.1 ms | +58.3 ms | 160.2 ms | 101.9 ms | 18405 | 12.2 s | 1509/s | 105 | 120 of 384 MB | 0 |
| 1200 | 1198.0 | 0 of 72001 | 0 | 0 | +9.8 ms | +39.4 ms | +78.4 ms | 180.3 ms | 101.9 ms | 39753 | 28.2 s | 1410/s | 109 | 136 of 384 MB | 0 |
| 1600 | 1388.9 | 3947 of 83508 | 3947 | 12492 | n/a | n/a | n/a | 2517.2 ms | 102.2 ms | 59796 | 43.3 s | 1381/s | 500 | 322 of 384 MB | 0 |
| 2000 | 1932.2 | 0 of 116326 | 0 | 3677 | n/a | n/a | n/a | 546.1 ms | 102.5 ms | 89566 | 57.8 s | 1550/s | 500 | 283 of 384 MB | 0 |

Highest rate sustained (no errors, no dropped requests): 1200/s
::notice title=full-stack load test::| 100 | 99.8 | 0 of 6001 | 0 | 0 | +3.7 ms | +7.2 ms | +10.8 ms | 113.2 ms | 102.4 ms | 0 | 0.1 s | - | 9 | 80 of 384 MB | 0 |
::notice title=full-stack load test::| 200 | 199.7 | 0 of 12000 | 0 | 0 | +3.4 ms | +11.1 ms | +20.7 ms | 122.8 ms | 102.1 ms | 0 | 0.2 s | - | 12 | 85 of 384 MB | 0 |
::notice title=full-stack load test::| 400 | 399.3 | 0 of 24001 | 0 | 0 | +5.9 ms | +24.1 ms | +42.4 ms | 144.3 ms | 101.9 ms | 0 | 0.1 s | - | 26 | 85 of 384 MB | 0 |
::notice title=full-stack load test::| 600 | 598.9 | 0 of 36001 | 0 | 0 | +5.6 ms | +24.2 ms | +47.1 ms | 149.0 ms | 101.9 ms | 0 | 0.1 s | - | 41 | 85 of 384 MB | 0 |
::notice title=full-stack load test::| 800 | 798.6 | 0 of 48001 | 0 | 0 | +5.7 ms | +25.4 ms | +50.0 ms | 151.9 ms | 101.9 ms | 5079 | 4.0 s | 1270/s | 51 | 85 of 384 MB | 0 |
::notice title=full-stack load test::| 1000 | 998.1 | 0 of 60002 | 0 | 0 | +7.0 ms | +30.1 ms | +58.3 ms | 160.2 ms | 101.9 ms | 18405 | 12.2 s | 1509/s | 105 | 120 of 384 MB | 0 |
::notice title=full-stack load test::| 1200 | 1198.0 | 0 of 72001 | 0 | 0 | +9.8 ms | +39.4 ms | +78.4 ms | 180.3 ms | 101.9 ms | 39753 | 28.2 s | 1410/s | 109 | 136 of 384 MB | 0 |
::notice title=full-stack load test::| 1600 | 1388.9 | 3947 of 83508 | 3947 | 12492 | n/a | n/a | n/a | 2517.2 ms | 102.2 ms | 59796 | 43.3 s | 1381/s | 500 | 322 of 384 MB | 0 |
::notice title=full-stack load test::| 2000 | 1932.2 | 0 of 116326 | 0 | 3677 | n/a | n/a | n/a | 546.1 ms | 102.5 ms | 89566 | 57.8 s | 1550/s | 500 | 283 of 384 MB | 0 |
::notice title=full-stack load test::
::notice title=full-stack load test::Highest rate sustained (no errors, no dropped requests): 1200/s
