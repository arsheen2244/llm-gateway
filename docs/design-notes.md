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

## 4. Mock provider: how scenarios are chosen

**Chosen:** random behaviour comes from env-configured rates (`MOCK_ERROR_RATE`,
`MOCK_MID_STREAM_FAILURE_RATE`, `MOCK_LATENCY_MS`, …), used by load tests. The model
name can also *force* an outcome (`mock-error-503`, `mock-fail-after-2`), which keeps
tests deterministic. The random source is an injected `DoubleSupplier`, so unit tests
pass a fixed sequence.

**Rejected:**
- *Request headers such as `X-Mock-Fail`:* the gateway should not forward arbitrary
  client headers to providers, so these would not survive a trip through the gateway.
- *An admin endpoint that changes mock state:* shared mutable state means tests running
  in parallel interfere with each other.
- *A separately configured mock instance per test:* slow, and only usable from tests,
  not from curl demos or k6.

**Trade-off:** test concerns leak slightly into model names. This only affects the
`mock-*` namespace, which never reaches a real provider.

## 5. Mock provider: what a "mid-stream failure" looks like

**Chosen:** an abrupt connection drop. After N chunks the handler throws, and because
the response is already committed, Tomcat aborts the connection without sending the
terminating chunk. The client sees an I/O error, with no error message and no `[DONE]`.

**Why:** this is the hardest case for the gateway to handle well. Providers can also
fail *politely* with an in-band error event (Anthropic sends `event: error`). The
Anthropic adapter handles that as part of its protocol, but the connection drop is
the scenario that proves the gateway never leaves a client hanging.

**Note:** the simulated exception captures no stack trace (writable stack trace off),
so forced failures in load tests don't flood the logs.
