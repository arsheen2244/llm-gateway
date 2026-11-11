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

## 6. The gateway's internal data model is the OpenAI wire format

**Chosen:** the records in `api.dto` (`ChatCompletionRequest`, `ChatCompletionResponse`,
…) are the public API *and* what flows through the pipeline. Adapters translate from
them to each provider's format, and the OpenAI adapter barely translates at all.

**Rejected:**
- *A separate "canonical" model:* a third layer of mapping code with no payoff while
  the public API is OpenAI-shaped. If we ever add a second public API format, that is
  when a canonical model earns its keep.
- *Forwarding raw bytes:* the lowest latency, but interceptors (cache, cost tracking)
  need to read the content.

**Consequence:** fields outside the supported subset (`tools`, `n`, `logprobs`, …) are
dropped silently. This is a known week-1 limit. A stricter gateway would reject
unknown fields with 400 so clients are never surprised.

## 7. Adapter = protocol, provider = instance

**Chosen:** config separates the protocol (`type: openai`) from the instance (base URL
and key). One `OpenAiAdapter` bean serves both the real OpenAI API and the mock.

**Rejected:** a dedicated `MockAdapter`. Then no test would ever run the real
adapters' HTTP, serialization and error-parsing code.

**Also:** keys enter config only through `${ENV_VAR:}` placeholders. Records that hold
keys override `toString()` to mask them, so a stray log line can't leak one. Startup
fails fast if a route points to an unknown provider or a provider has an unknown type.
A provider with no key fails per request (503 `provider_not_configured`), so the
gateway still runs with only the mock configured.

## 8. Routing: ordered list, first match wins

**Chosen:** `routes` is an ordered list of `model-pattern → provider`. A pattern is an
exact name or a prefix ending in `*`. Unknown model → 404 `model_not_found` (OpenAI's
own behaviour).

**Rejected:** longest-match or regex routing. Both are more powerful, but you can't
tell which rule applies just by reading the file. With an ordered list the answer is
always "the first one that matches", the same rule firewalls and nginx locations use.

## 9. Error mapping

| Provider said | Client gets | Why |
|---|---|---|
| 400 / 404 / 422 | same status, provider's message | The client's mistake, and the client can fix it |
| 401 / 403 | 502, generic message | *Our* key is wrong. The provider's message can include part of the key, so it is hidden |
| 429 | 429 | Tells the client to back off |
| 503 / 529 (Anthropic "overloaded") | 503 | Temporary unavailability |
| other 5xx | 502 | Bad gateway: our upstream failed |
| timeout | 504 | Gateway timeout |
| connection failure / unparseable reply | 502 | |
| no API key configured | 503 | |

All errors use OpenAI's `{"error": {message, type, param, code}}` shape, so
OpenAI client libraries can parse them. The `code` field holds gateway-specific
values such as `provider_timeout` that tell clients what went wrong.

`ProviderException.isRetryable()` (429, 5xx, timeouts, I/O) exists now but is unused.
It is the seam for week 3's retries and failover.

## 10. Interceptor chain (the seam for weeks 2–5)

**Chosen:** a `ChatInterceptor` interface run by an immutable `InterceptorChain`, the
same pattern as a servlet `FilterChain`. Each interceptor can act before or after the
provider call, or short-circuit it. Spring injects all interceptor beans in `@Order`
order. Week 1 has none in production; a unit test proves ordering and short-circuiting.

**Rejected:** a servlet `Filter` or Spring `HandlerInterceptor`. Those run before the
body is parsed and before routing, so they can't see the model, the provider, or
(later) token counts and response chunks, which rate limiting, caching and cost
tracking all need. Servlet filters are still the right place for HTTP-level concerns
such as request IDs (step 7).
