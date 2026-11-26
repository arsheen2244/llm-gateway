| Rate (req/s) | Achieved | Failed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 99.8 | 0 of 6001 | 0 | +3.3 ms | +8.7 ms | +12.9 ms | 115.1 ms | 102.2 ms | 0 | 0.2 s | - |
| 200 | 199.7 | 0 of 12001 | 0 | +3.1 ms | +13.9 ms | +27.6 ms | 129.5 ms | 101.9 ms | 0 | 0.2 s | - |
| 400 | 399.3 | 0 of 24000 | 0 | +4.3 ms | +19.5 ms | +45.8 ms | 147.7 ms | 101.9 ms | 0 | 0.1 s | - |
| 600 | 598.9 | 0 of 36001 | 0 | +5.2 ms | +25.5 ms | +62.5 ms | 164.5 ms | 102.0 ms | 2378 | 2.8 s | 849/s |
| 800 | 798.6 | 0 of 48001 | 0 | +8.2 ms | +39.0 ms | +93.3 ms | 195.4 ms | 102.1 ms | 18022 | 18.8 s | 959/s |
| 1000 | 998.3 | 0 of 60000 | 0 | +15.2 ms | +53.7 ms | +93.5 ms | 196.0 ms | 102.5 ms | 34608 | 36.0 s | 961/s |
| 1200 | 832.9 | 163 of 53458 | 18543 | n/a | n/a | n/a | 14020.5 ms | 102.6 ms | 27163 | 28.4 s | 956/s |
| 1600 | 435.1 | 5812 of 39174 | 56701 | n/a | n/a | n/a | 9563.0 ms | 103.4 ms | 0 | 0.4 s | - |
| 2000 | 310.6 | 8000 of 23994 | 96008 | n/a | n/a | n/a | 316.2 ms | 156.5 ms | 0 | 0.3 s | - |

Highest rate sustained (no errors, no dropped requests): 1000/s
::notice title=full-stack load test::| 100 | 99.8 | 0 of 6001 | 0 | +3.3 ms | +8.7 ms | +12.9 ms | 115.1 ms | 102.2 ms | 0 | 0.2 s | - |
::notice title=full-stack load test::| 200 | 199.7 | 0 of 12001 | 0 | +3.1 ms | +13.9 ms | +27.6 ms | 129.5 ms | 101.9 ms | 0 | 0.2 s | - |
::notice title=full-stack load test::| 400 | 399.3 | 0 of 24000 | 0 | +4.3 ms | +19.5 ms | +45.8 ms | 147.7 ms | 101.9 ms | 0 | 0.1 s | - |
::notice title=full-stack load test::| 600 | 598.9 | 0 of 36001 | 0 | +5.2 ms | +25.5 ms | +62.5 ms | 164.5 ms | 102.0 ms | 2378 | 2.8 s | 849/s |
::notice title=full-stack load test::| 800 | 798.6 | 0 of 48001 | 0 | +8.2 ms | +39.0 ms | +93.3 ms | 195.4 ms | 102.1 ms | 18022 | 18.8 s | 959/s |
::notice title=full-stack load test::| 1000 | 998.3 | 0 of 60000 | 0 | +15.2 ms | +53.7 ms | +93.5 ms | 196.0 ms | 102.5 ms | 34608 | 36.0 s | 961/s |
::notice title=full-stack load test::| 1200 | 832.9 | 163 of 53458 | 18543 | n/a | n/a | n/a | 14020.5 ms | 102.6 ms | 27163 | 28.4 s | 956/s |
::notice title=full-stack load test::| 1600 | 435.1 | 5812 of 39174 | 56701 | n/a | n/a | n/a | 9563.0 ms | 103.4 ms | 0 | 0.4 s | - |
::notice title=full-stack load test::| 2000 | 310.6 | 8000 of 23994 | 96008 | n/a | n/a | n/a | 316.2 ms | 156.5 ms | 0 | 0.3 s | - |
::notice title=full-stack load test::
::notice title=full-stack load test::Highest rate sustained (no errors, no dropped requests): 1000/s
