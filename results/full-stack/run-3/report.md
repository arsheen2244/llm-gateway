| Rate (req/s) | Achieved | Failed | Dropped | Added p50 | Added p95 | Added p99 | Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in | Consumer rate |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 99.8 | 0 of 6001 | 0 | +3.7 ms | +8.8 ms | +13.7 ms | 116.3 ms | 102.6 ms | 0 | 0.2 s | - |
| 200 | 199.7 | 0 of 12001 | 0 | +3.2 ms | +13.3 ms | +31.3 ms | 133.4 ms | 102.1 ms | 0 | 0.2 s | - |
| 400 | 399.3 | 0 of 24001 | 0 | +4.2 ms | +21.7 ms | +47.9 ms | 149.9 ms | 101.9 ms | 0 | 0.1 s | - |
| 600 | 598.9 | 0 of 36000 | 0 | +5.1 ms | +19.7 ms | +56.7 ms | 158.7 ms | 102.0 ms | 2950 | 3.5 s | 843/s |
| 800 | 798.4 | 0 of 48001 | 0 | +7.1 ms | +21.1 ms | +35.1 ms | 137.2 ms | 102.1 ms | 16936 | 18.1 s | 936/s |
| 1000 | 997.9 | 0 of 60001 | 0 | +15.3 ms | +55.5 ms | +84.7 ms | 187.1 ms | 102.4 ms | 34565 | 35.7 s | 968/s |
| 1200 | 1196.0 | 0 of 71946 | 64 | n/a | n/a | n/a | 293.2 ms | 102.5 ms | 48754 | 51.0 s | 956/s |
| 1600 | 420.8 | 6219 of 37876 | 58118 | n/a | n/a | n/a | 10054.6 ms | 103.8 ms | -3 | 0.3 s | - |
| 2000 | 312.7 | 8000 of 23995 | 96005 | n/a | n/a | n/a | 244.7 ms | 164.4 ms | 0 | 0.4 s | - |

Highest rate sustained (no errors, no dropped requests): 1000/s
::notice title=full-stack load test::| 100 | 99.8 | 0 of 6001 | 0 | +3.7 ms | +8.8 ms | +13.7 ms | 116.3 ms | 102.6 ms | 0 | 0.2 s | - |
::notice title=full-stack load test::| 200 | 199.7 | 0 of 12001 | 0 | +3.2 ms | +13.3 ms | +31.3 ms | 133.4 ms | 102.1 ms | 0 | 0.2 s | - |
::notice title=full-stack load test::| 400 | 399.3 | 0 of 24001 | 0 | +4.2 ms | +21.7 ms | +47.9 ms | 149.9 ms | 101.9 ms | 0 | 0.1 s | - |
::notice title=full-stack load test::| 600 | 598.9 | 0 of 36000 | 0 | +5.1 ms | +19.7 ms | +56.7 ms | 158.7 ms | 102.0 ms | 2950 | 3.5 s | 843/s |
::notice title=full-stack load test::| 800 | 798.4 | 0 of 48001 | 0 | +7.1 ms | +21.1 ms | +35.1 ms | 137.2 ms | 102.1 ms | 16936 | 18.1 s | 936/s |
::notice title=full-stack load test::| 1000 | 997.9 | 0 of 60001 | 0 | +15.3 ms | +55.5 ms | +84.7 ms | 187.1 ms | 102.4 ms | 34565 | 35.7 s | 968/s |
::notice title=full-stack load test::| 1200 | 1196.0 | 0 of 71946 | 64 | n/a | n/a | n/a | 293.2 ms | 102.5 ms | 48754 | 51.0 s | 956/s |
::notice title=full-stack load test::| 1600 | 420.8 | 6219 of 37876 | 58118 | n/a | n/a | n/a | 10054.6 ms | 103.8 ms | -3 | 0.3 s | - |
::notice title=full-stack load test::| 2000 | 312.7 | 8000 of 23995 | 96005 | n/a | n/a | n/a | 244.7 ms | 164.4 ms | 0 | 0.4 s | - |
::notice title=full-stack load test::
::notice title=full-stack load test::Highest rate sustained (no errors, no dropped requests): 1000/s
