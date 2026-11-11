# Design notes

Each significant decision: what was chosen, what was rejected, and why.

## 1. Project layout: one Maven multi-module build, two apps

**Chosen:** a parent POM with two modules, `gateway` and `mock-provider`. They build
together but run as separate processes (separate containers in Docker Compose).

**Rejected:**
- *Two separate repositories:* cross-module changes (for example a new mock scenario
  plus the test that uses it) would need two commits in two places.
- *A shared `common` module with DTOs used by both apps:* the mock stands in for an
  external provider, so it must not share code with the gateway. If both used the same
  classes, a mismatch between our adapter and the real wire format would go unnoticed.

**Detail:** the gateway depends on `mock-provider` in `test` scope only, so integration
tests can start the real mock in-process. To make this work, the mock's runnable fat jar
has the classifier `-exec` and the plain jar stays the main artifact. The mock also has
no `application.yml`, because two on one test classpath would clash.

## 2. Spring MVC on virtual threads (not WebFlux)

**Chosen:** `spring.threads.virtual.enabled=true`. Each request, including a long SSE
stream, holds one virtual thread and is written as plain blocking code.

**Rejected:** WebFlux/Reactor. It handles many concurrent streams well, but the
operator-chain style is harder to read, debug and explain. Virtual threads give similar
scalability for I/O-bound work: a blocked virtual thread unmounts from its carrier OS
thread, so waiting is cheap.

**Trade-off to know:** virtual threads can get *pinned* to their carrier thread while
inside a `synchronized` block doing I/O (this was fixed in JDK 24). We are on JDK 21,
so code on the request path avoids `synchronized` around blocking calls.

## 3. Spring Boot 3.5.x

As requested. Boot 4 exists. Moving to it is a known follow-up, not a week-1 concern.
