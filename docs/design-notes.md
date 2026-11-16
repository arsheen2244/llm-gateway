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

## 11. HTTP client for provider calls: HttpURLConnection, chosen by measurement

> **Superseded by note 16.** HttpURLConnection was later replaced by Apache HttpClient 5
> after the benchmark found a 15 ms per-call cost. This note is kept because its timeout
> measurement still explains why the JDK HttpClient was ruled out.

**Chosen (at the time):** Spring's `SimpleClientHttpRequestFactory` (built on `HttpURLConnection`),
with a connect timeout and a *read* timeout. The read timeout is the maximum silence
between bytes.

**What I planned first, and why it changed:** the plan said `JdkClientHttpRequestFactory`
(built on `java.net.http.HttpClient`). Before writing streaming I tested both against a
local server that streams slowly, with a 1-second read timeout:

| Client | Healthy stream (6 lines, 300 ms apart, 1.8 s total) | Stalled stream (2.5 s gap) |
|---|---|---|
| `JdkClientHttpRequestFactory` | **killed at ~1.3 s** | killed at ~1.0 s |
| `SimpleClientHttpRequestFactory` | completed | killed at ~1.0 s |

So the JDK factory's "read timeout" caps the **whole response**. With 60 s configured,
any LLM answer that streams longer than a minute would be cut off. `HttpURLConnection`'s
timeout is a socket read timeout: it resets with every byte, which is exactly the
"detect a stalled stream" behaviour we want.

**Rejected:**
- *Apache HttpClient 5:* same idle-timeout semantics, plus explicit connection-pool
  limits. It is a good upgrade when week 3 or the load tests need pool limits, but for
  now it is a new dependency without a need.
- *JDK client plus a hand-written idle watchdog:* more code to get right and test.

**Trade-offs to know:** no HTTP/2 (not needed for provider calls), and connection reuse
relies on the JDK's keep-alive cache, with no explicit pool size. On virtual threads:
the JDK's HTTP protocol handler moved from `synchronized` to `ReentrantLock` in JDK 18
(JDK-8229867), so blocking reads don't pin carrier threads on our JDK 21.

## 12. Streaming: blocking relay, lazy commit, and three kinds of failure

**Chosen:** the controller hands the response to `StreamingChatHandler`. On the
request's own virtual thread, the adapter reads the provider's SSE stream line by
line (`SseEventReader`) and pushes each chunk through a `ChunkSink` to `SseResponse`,
which writes and flushes one frame per chunk. It is plain blocking code, one thread per
stream.

**Lazy commit:** `SseResponse` sends nothing, not even the status line, until the first
frame. That makes the failure handling depend on *when* the failure happens:

| When it fails | What the client gets |
|---|---|
| Before the first byte (provider 5xx, timeout, refused) | A normal HTTP error (e.g. 503) with a JSON body, the same as non-streaming |
| After the first byte (provider dropped the connection, sent an in-band error, or ended without `[DONE]`) | The 200 is already sent, so `data: {"error":{…,"code":"stream_interrupted"}}`, then the response ends cleanly **without** `[DONE]`. OpenAI's SDKs raise an exception on this frame |
| The client disconnects | Logged at INFO. The write fails, the exception unwinds through the adapter, and `ProviderStreams` aborts the provider connection instead of draining it (see note 16), so we stop receiving (and paying for) tokens nobody will read |

**Rejected:**
- *`SseEmitter`:* designed for pushing events from *another* thread (an async
  handoff). With virtual threads we can block the request thread, so the handoff is
  pure complexity.
- *`StreamingResponseBody`:* works, but runs the write loop as an async dispatch on a
  task executor. That is harder to trace and gives no benefit on virtual threads.
- *Always sending 200 and reporting every failure as an error event:* clients would
  lose the ability to tell "provider down" (retry later) from "bad request" using the
  status code.

**Usage chunk:** the adapter always asks OpenAI for the final usage-only chunk
(`stream_options.include_usage`), because week 5's cost accounting needs real token
counts for every request. The handler forwards that chunk to the client only if the
client asked for it, which is what OpenAI itself does.

**A bug the tests caught:** the streaming adapter sends `Accept: text/event-stream`.
When the mock answered with an *error*, Spring refused to write a JSON body for that
`Accept` and returned 406 instead. Both the mock and the gateway's
`ApiExceptionHandler` now set `Content-Type: application/json` explicitly on errors,
as real providers do.

## 13. Anthropic adapter: what is translated, and what deliberately isn't

| OpenAI concept | Anthropic concept | How the adapter handles it |
|---|---|---|
| `system` messages in the list | top-level `system` field | All system messages are extracted and joined with blank lines, in order |
| `max_tokens` optional | `max_tokens` **required** | Defaults to 4096 (safe for every current Claude model) |
| `stop` | `stop_sequences` | Renamed |
| `temperature` 0–2 | `temperature` 0–1 | **Passed through unchanged.** Rescaling would silently change what the client asked for. An out-of-range value gets Anthropic's own 400, which reaches the client with its message |
| `finish_reason` | `stop_reason` | `end_turn`/`stop_sequence` → `stop`, `max_tokens` → `length`, `tool_use` → `tool_calls`, `refusal` → `content_filter`, unknown → `stop` |
| `prompt_tokens` | `input_tokens` + cache tokens | Anthropic counts prompt-cache tokens separately. They are added in, so week 5's cost accounting sees everything the model processed |
| Uniform `chat.completion.chunk` stream | Named events (`message_start`, `content_block_delta`, `message_delta`, `message_stop`, `error`, `ping`) | `AnthropicStreamTranslator`, a small per-stream state machine |
| `Authorization: Bearer` | `x-api-key` + `anthropic-version` | Version pinned to `2023-06-01`, the version this adapter was written against |

**Structure:** the adapter keeps HTTP separate from translation. `AnthropicMapper` and
`AnthropicStreamTranslator` do no I/O, so they are covered by plain unit tests. The
adapter itself is covered by integration tests against the mock's Anthropic endpoint.
`ProviderStreams.consume` holds the stream-reading rules both adapters share (always
close the body, and decide when a stream counts as broken). Each adapter only says what
its "finished" marker is: `[DONE]` for OpenAI, `message_stop` for Anthropic.

## 14. Request IDs and structured logs

**Chosen:**
- `RequestIdFilter`, a servlet filter that runs first, gives each request an ID. It
  reuses the client's `X-Request-Id` if that is 1–64 characters of `[A-Za-z0-9._-]`,
  otherwise it generates a UUID. The ID goes into the **MDC** and back to the client in
  the response header.
- The pipeline adds `model`, `provider` and `stream` to the MDC. The streaming handler
  adds `streamOutcome` (`completed` / `interrupted` / `client_disconnected`).
- The filter writes **one access-log line per request** (logger `access`) with method,
  path, status and duration, plus everything in the MDC.
- Logs are JSON using Spring Boot's built-in ECS format
  (`logging.structured.format.console: ecs`).

**Why validate the incoming ID:** it is copied into every log line. A value containing a
newline could forge log entries (log injection), and an unbounded one bloats logs.

**Why `streamOutcome`:** a stream that fails halfway was already sent `200`, so the
status alone would hide the failure. The extra field makes broken streams queryable.

**Why MDC works here:** the MDC is a `ThreadLocal`. With one (virtual) thread per
request, everything logged while handling a request is on that thread, so it carries
the ID automatically. If work ever moves to other threads (week 3's hedged requests),
the MDC must be copied to those threads explicitly. That is a known follow-up.

**Rejected:**
- *logstash-logback-encoder:* the same JSON output, but an extra dependency, now that
  Spring Boot 3.4+ does this itself.
- *Micrometer Tracing / OpenTelemetry trace IDs:* the right tool once there are several
  services to trace across. In week 1 one gateway and a mock don't need it, and week 5
  (metrics) is the natural place to add it.
- *Forwarding the request ID to providers:* real providers ignore unknown headers, and
  their own request IDs (e.g. OpenAI's `x-request-id` response header) are the ones
  their support teams can look up. Logging the provider's ID is a possible later
  addition.

## 15. Benchmark findings (week 1)

The first measurement on one Windows laptop showed about **15 ms at p50 and 18–24 ms at
p95**, with 0% errors. That turned out to be a bug in the HTTP client setup (note 16).
After the fix, the gateway adds **about 1–2 ms at p50 and 3–5 ms at p95** (full tables
in the README).

**What the benchmark proved besides the numbers:**
- **No virtual-thread pinning under load.** With `-Djdk.tracePinnedThreads=short` the
  gateway reported zero pinned threads during the run, both with `HttpURLConnection` (note
  11) and after the switch to Apache HttpClient 5 (note 16).
- **Virtual threads cost nothing measurable here.** The same benchmark with
  `spring.threads.virtual.enabled=false` (Tomcat's platform-thread pool) gave the same
  13–17 ms overhead. They were chosen for scaling many open streams, not for per-request
  speed, and this result is consistent with that.
- **A cold JVM needs a longer warm-up than 15 s.** The first run's first gateway phase
  had a 71 ms p95 overhead that disappeared on the second run.

**The constant ~15 ms:** resolved, see note 16.

**Measurement lesson:** with a mock that sleeps, Windows rounds each `Thread.sleep` up to
its 15.6 ms timer tick, so the mock's own timing moves by up to ~15 ms. After the fix, that
noise was larger than the gateway's real cost and even produced negative "overhead" values.
The headline overhead is therefore measured against a **zero-latency** mock, where nothing
sleeps.

## 16. HTTP client, revisited: Apache HttpClient 5 (Nagle off)

**The problem, found by investigation rather than guessed:**
1. The ~15 ms overhead was the same one request at a time as under load, so it was a fixed
   per-request cost, not contention.
2. The gateway's own access log measured ~16 ms more than the mock took, so the time was
   spent *inside* the gateway's handling, not at the network edges.
3. Turning virtual threads off changed nothing, which ruled them out.
4. A standalone probe with plain `HttpURLConnection` (no Spring) reproduced it exactly:
   **62.5 ms** when the body is buffered (headers and body in one write) and **78 ms** with
   fixed-length or chunked streaming (headers, then body, as separate writes).
5. Spring 6.2's `SimpleClientHttpRequest` (checked in the bytecode) always uses one of
   the two streaming modes when there is a body, and has no option to buffer.

The likely mechanism is Nagle's algorithm (left on by `HttpURLConnection`) holding the
small second write until the first is acknowledged, while the receiver delays its ACK.
What was proven is the effect (split writes cost ~16 ms here). The exact TCP mechanism is
inferred, not measured.

**Chosen:** Apache HttpClient 5 through Spring's `HttpComponentsClientHttpRequestFactory`,
with:
- **TCP_NODELAY on** (Nagle off), which is the fix;
- **socket timeout = `timeouts.read`**, the same "max silence between bytes" semantics
  note 11 required for long streams;
- an explicit **connection pool** (500 total, 200 per provider), which weeks 2 and 3 will
  need anyway;
- **automatic retries off**, because retrying is week 3's policy decision, not something
  the HTTP library should do silently.

**Rejected:**
- *Our own `ClientHttpRequestFactory` that lets `HttpURLConnection` buffer:* measured to fix
  the delay, with no new dependency, but it means maintaining a hand-written HTTP adapter.
- *Documenting it and moving on:* the README number would stay inflated by an avoidable bug.

**A trap this switch introduced, and how it is handled:** when a response stream is closed
early, Apache HttpClient *reads the rest of it* so the connection can be reused.
`HttpURLConnection` dropped the connection instead. Left alone, a client that disconnects
mid-stream would make the gateway keep downloading (and paying for) the provider's whole
answer. `ProviderStreams.consume` therefore calls `abort()` on the body when reading
stops early.

**Regression tests** (`ProviderHttpClientTest`, using the real client config against a
deliberately slow local server):
- a 2.4 s stream with 300 ms gaps is **not** cut off by a 1 s read timeout;
- 3 s of silence **is** cut off;
- a client disconnect returns in under 1 s instead of draining a 3 s stream;
- small requests to an instant server have a median under 10 ms (Nagle would cost 16–40 ms).

**Result:** overhead at the same 50 ms setup fell from 15.3 ms to 0.6 ms (p50,
non-streaming). With a zero-latency mock the gateway adds about 1–2 ms at p50.

## 17. Client authentication: hashed API keys, locked by default

**Chosen:**
- Clients send a gateway key as `Authorization: Bearer <key>`, which is what OpenAI's SDKs
  already send, so existing clients only change their base URL and key.
- Clients live in config under `gateway.auth.clients`, each with an id, a **SHA-256 hash**
  of its key, and its limits (`requests-per-minute`, `tokens-per-minute`).
- `ApiKeyAuthFilter`, a servlet filter right after `RequestIdFilter`, rejects a missing
  or unknown key with an OpenAI-style 401 (`missing_api_key` / `invalid_api_key`)
  **before the body is parsed**. A valid key attaches a `ClientIdentity` (id + limits) to
  the request. The controller passes it into `ChatContext`, where the rate limiter reads
  it. The client id is also added to the MDC, so every log line says who called.
- **The default config has no clients, so every request is rejected.** A `local` profile
  defines demo clients with deliberately public keys, for local runs and Docker Compose.

**Why hashes, not keys:** a config file is copied around (repos, CI logs, support
tickets). With hashes, a leaked config grants nothing. The presented key is hashed and the
hash is looked up in a map, so there is no character-by-character comparison of secrets
whose timing could leak how much of a guess was right.

**Why locked by default:** a gateway deployed with real provider keys but no client config
must not be an open proxy to paid APIs. Forgetting configuration should fail safe.

**Why a filter, not an interceptor:** auth needs nothing from the parsed body and should
reject as cheaply as possible (note 10). The rate limiter is the opposite case, so it
goes in the interceptor chain.

**Rejected:**
- *Spring Security:* the right tool once there are users, sessions, roles or OAuth. For
  "look up one bearer key" it adds a large framework and its own filter chain to explain,
  for about 40 lines of benefit.
- *Plaintext keys in config, even from environment variables:* simpler, but every place
  that can read the config can impersonate every client.
- *Salted slow hashes (bcrypt/argon2):* those protect low-entropy *passwords* against
  offline guessing. API keys should be long random strings, where SHA-256 is enough, and
  a slow hash on every request would add milliseconds of CPU per call.

**Known limit:** keys come from config, so adding or revoking a client needs a restart
(or a config refresh). A key store (database or Redis) is the natural next step.

## 18. Rate limiting algorithm: sliding window log, one contract for two implementations

**Chosen:** a **sliding window log**. Each client has a time-ordered log of admitted
requests, each with its token count, plus a running token total. On every check, entries
older than the window are dropped (and their tokens subtracted). The request is admitted
only if *both* limits still hold with it included.

Rules (in the `RateLimiter` javadoc, and enforced by `RateLimiterContractTest`):
- An entry admitted at `t` counts while `t > now - window`, so it leaves exactly at
  `t + window`.
- **Rejected requests are not recorded.** If they were, a client that keeps retrying
  would keep pushing its own recovery further away.
- `retryAfter` is the earliest moment the *same* request would fit. It is found by
  walking entries oldest-first, pretending each has expired, until both limits fit.
  For tokens this can mean waiting for several entries, not just the oldest one.
- An estimate larger than the entire token limit is `TOO_LARGE`. It can never fit, so
  telling the client to retry would be a lie.
- `reconcile` replaces an entry's reserved tokens with the actual usage. If the entry
  has already left the window it is a no-op, because it no longer counts anyway.

**Rejected:**
- *Fixed window* (one counter per calendar minute): allows bursts of up to 2× the limit
  around the minute boundary.
- *Sliding window counter* (two fixed buckets, the previous one weighted): O(1) memory,
  but approximate. Its error would mix into the drift measurement, which is meant to
  show the effect of *distribution* (shared Redis vs per-instance memory).
- *Token bucket:* great for smoothing traffic, but "N per rolling minute" is the limit
  people actually configure and reason about, and it maps to the log directly.

**Memory bound:** because rejected requests are not stored, a client's log holds at most
`maxRequests` entries. At 600 requests per minute, that is 600 small entries per client.

**One contract, two implementations:** `RateLimiterContractTest` is abstract. The
in-memory limiter runs it with a fake clock (exact timing). The Redis limiter runs the
same cases for real, because its script reads Redis's clock, with a tolerance on
measured durations. The in-memory limiter is therefore also the executable reference for
the Lua script's semantics.

**The tests were checked against planted bugs:** an off-by-one at the window edge
(`<` instead of `<=`) fails 3 tests, and a `retryAfter` that only waits for the oldest
entry fails the multi-expiry test. A suite that has never failed hasn't shown it can catch
anything.

**Concurrency:** the in-memory limiter locks per client with a `ReentrantLock`, not
`synchronized`, following note 2's rule for virtual threads on JDK 21. The contract
includes a test where 100 simultaneous callers against a limit of 10 must admit exactly 10.

## 19. Rate limiting in the pipeline: estimate, reserve, reconcile

**Where it runs:** `RateLimitInterceptor`, first in the `ChatInterceptor` chain (order 100).
It needs the parsed request (to estimate tokens) and the response's `usage` (to reconcile),
so it can't be a servlet filter (note 10). Being first means a refused request costs
nothing else.

**Token estimate (owner's choice):** prompt = characters ÷ 4, rounded up. Completion = the
client's `max_tokens` if set, otherwise `gateway.rate-limit.default-completion-tokens`
(1000).
- *Why this:* no dependency, near-zero CPU, and `max_tokens` is a true upper bound when
  the client sets it.
- *Known weak spot:* without `max_tokens`, a reply longer than the default is
  under-reserved. Reconciling afterwards corrects the count, but cannot undo the overshoot
  that already happened, so a client can briefly exceed its token limit by up to (actual −
  default) per in-flight request.
- *Rejected:* worst-case reservation (the provider maximum when `max_tokens` is unset)
  over-reserves so much that clients hit 429s long before they use their budget. A real
  tokenizer (jtokkit) is exact only for OpenAI prompts (Anthropic's tokenizer isn't
  public) and still has to guess the completion length.

**Reconcile rules:**

| How the call ended | Reconciled to | Why |
|---|---|---|
| Usage reported (response body, or the stream's final usage chunk) | actual total | The truth is known |
| Provider failed before any output (HTTP error, timeout, connection failure) | 0 tokens; the request still counts | Providers don't bill these. The request slot stays used, so a failing client can't retry without limit |
| Stream broke mid-way, invalid response, client disconnected, or no usage | the estimate (unchanged) | We can't know what was billed, so we stay conservative |

A failing reconcile (for example Redis briefly down) is logged and never fails the client's
request. The request already happened, and accounting must not turn a success into an error.

**Responses:**
- Allowed: OpenAI's header names `x-ratelimit-limit-requests`,
  `x-ratelimit-remaining-requests`, `x-ratelimit-limit-tokens` and
  `x-ratelimit-remaining-tokens`, so existing clients and SDKs understand them. The pipeline
  is HTTP-free, so the interceptor writes them into a per-request `ResponseHeaders` object.
  The controller copies them onto normal responses, and `SseResponse` copies them when the
  stream commits (lazy commit makes that possible).
- Over a limit: **429** `rate_limit_exceeded` (type `rate_limit_error`), the same headers,
  and `Retry-After` in whole seconds, **rounded up**, so a client that waits exactly that
  long will fit. The message says which limit was hit.
- Estimate larger than the whole token limit: **429** `request_too_large_for_limit`
  **without** `Retry-After`, because waiting can never help. The message suggests
  lowering `max_tokens`. *Rejected:* 413, which means "request body too large in bytes",
  a different thing.

**Effect on the week-1 benchmark:** the limiter now sits in every request's path. The
`load-test` demo client has limits high enough never to throttle, so the overhead
benchmark still measures the gateway, now including the limiter.

## 20. Redis backend: one atomic script, Redis's clock, keys that stay together

**Chosen:** `RedisRateLimiter` keeps each client's window in Redis, so every gateway
instance sees the same counts. All the logic lives in two Lua scripts (`acquire.lua`,
`reconcile.lua`; note 21). The Java class only builds keys and
arguments and translates the replies.

**Why a Lua script:** Redis runs a script as one atomic step. "Check both limits, then
record the request" therefore cannot interleave with another instance doing the same.
Separate commands (`ZCARD`, then `ZADD`) would let two instances both see "9 of 10 used"
and both admit, a classic race.
*Rejected:* `MULTI`/`EXEC` transactions with `WATCH`, which are optimistic. Under
contention on one hot key they retry repeatedly, and the check-then-act logic would live
in Java across several round trips.

**Why Redis's clock (`TIME`):** three gateway instances have three slightly different
clocks. If each passed its own "now", the same entry would expire at different moments
depending on which instance looked. Redis is the one shared reference. (Since Redis 5,
scripts are replicated by their effects, so calling `TIME` inside a script is safe.)

**Key layout:** `rl:{clientId}:log` (sorted set of entries scored by admit time),
`rl:{clientId}:tokens` (hash: entry → tokens), `rl:{clientId}:sum` (running total).
- The `{clientId}` part is a **Redis Cluster hash tag**. Only the text in braces decides
  which node owns a key, so a client's three keys always share a node, which a multi-key
  script requires. It costs nothing on a single Redis and keeps the design cluster-ready.
- Client ids are validated to `[A-Za-z0-9._-]`, so an id can't inject braces or `:`.
- Keys get a TTL of one window on every admit, so a client that goes quiet leaves nothing
  behind.

**Sending scripts:** Spring's `RedisScript` uses `EVALSHA` (the script's SHA-1), so the
body isn't re-sent on every call. If Redis doesn't know the script yet (restart, new
node), Spring falls back to `EVAL` automatically.

**Timeouts:** command timeout 100 ms and connect timeout 200 ms. A slow or dead Redis must
not add seconds to every request. What happens when Redis is unreachable is note 22.

**Testing without Docker locally:** the Redis tests look for Redis in this order:
`REDIS_URL`, then a Testcontainers Redis if Docker is available (CI), otherwise they are
skipped. `RedisRateLimiterTest` runs the same `RateLimiterContractTest` cases as the
in-memory limiter. `RedisScriptTest` adds storage, TTL and cleanup checks, plus two limiter
instances on separate connections racing 100 requests against a limit of 10.

**Default backend:** `in-memory` in `application.yml`, so tests and single-instance runs
need no Redis. Multi-instance deployments must set `gateway.rate-limit.backend=redis`
(Docker Compose does).

## 21. The Lua scripts: how acquire and reconcile work

The project owner planned to write these, then asked for them to be written for them, so
this note explains them step by step.

**`acquire.lua`, in order:**
1. **Read Redis's clock:** `TIME` returns seconds and microseconds, converted to whole
   milliseconds.
2. **Expire old entries:** find entries with score ≤ now − window
   (`ZRANGEBYSCORE ... LIMIT 0 500`), look up their tokens (`HMGET`), remove them from the
   log (`ZREM`) and the hash (`HDEL`), and subtract their tokens from the running sum. This
   repeats in batches of 500 until none are left.
3. **Never fits?** If the estimate is above the whole token limit, return `-1` and store
   nothing.
4. **Fits?** If `count + 1 ≤ max_requests` and `sum + estimate ≤ max_tokens`, store the
   entry in all three keys (`ZADD`, `HSET`, `INCRBY`) and set each key's expiry to one
   window (`PEXPIRE`).
5. **Rejected:** store nothing. Walk entries oldest-first, 100 at a time
   (`ZRANGE ... WITHSCORES` + `HMGET`), subtracting each one's count and tokens, until
   the request would fit. `retry_after_ms` is when that entry really leaves:
   `admitted_at + window − now`.

**`reconcile.lua`:** the same expiry, then look the entry up in the hash. If it's gone,
change nothing and return `{0, sum}`. If it's there, store the actual tokens and adjust the
sum by the difference (`INCRBY`, which keeps the key's expiry).

**Details worth being able to explain:**
- **Why batches:** Lua's `unpack()` spreads a table into function arguments, and Lua has a
  limit of a few thousand arguments per call. The `load-test` client may hold up to a
  million entries, so the scripts never unpack an unbounded list.
- **Why `SET ... KEEPTTL`:** a plain `SET` replaces a key's value *and removes its expiry*.
  Without `KEEPTTL`, writing the reduced sum back would make the sum key live forever
  (Redis 6+).
- **The running sum vs summing the hash every time:** `HVALS` plus adding everything up
  would be simpler, but costs O(entries) on every request. The running sum makes the
  common path (admit) constant-time apart from expiry, which only touches entries that
  actually expired.
- **`false` from Redis:** a missing hash field comes back to Lua as `false`, not `nil`.
  `tonumber(false)` is `nil`, and the `or 0` turns it into a safe 0.
- **Integers only in replies:** Redis converts Lua numbers to integers in a reply. The Java
  side rejects any other shape with a clear message.
- **Duplicated expiry code:** Lua scripts cannot import each other. Redis 7 *Functions*
  (`FUNCTION LOAD`) can share code, but they have to be loaded into Redis as a separate
  deployment step. Two copies of 20 lines was the simpler trade.

**Cost per call:** a handful of commands on one client's keys, plus work proportional to
the number of entries that expired since the last call. The rejected path additionally
walks only as many entries as it needs to.

## 22. When Redis is unreachable: fall back to a local share of each limit

**Owner's choice:** fail open, but not unlimited. Each instance falls back to its own
in-memory limiter enforcing `ceil(limit / instances)`.

**How it works (`FallbackRateLimiter`, wrapping the Redis limiter):**
- **Trigger:** a `DataAccessException` from Redis (connection refused, or no reply within
  the 100 ms command timeout). **Only these.** Any other exception, such as a script returning
  the wrong shape, is a bug and is rethrown. A fallback that hides bugs would keep a broken
  limiter "working" forever.
- **While degraded,** Redis is *not* tried on every request (that would add 100 ms to each).
  It is probed at most once per `gateway.rate-limit.redis-retry-interval` (5 s). An atomic
  compare-and-set lets exactly one request per interval be the probe, and the first success
  switches back. This is a deliberately minimal circuit breaker; the general one is week 3.
- **Reconcile** tries Redis first. If Redis doesn't know the reservation, it was made
  locally during the outage, so the local limiter reconciles it. No bookkeeping of where a
  reservation came from is needed.
- **Visibility:** a WARN when falling back, an INFO when recovering, and an MDC field
  `rateLimiter` (`shared` / `local-fallback`) on every log line, including the access log.

**Why round the share up:** with `rpm: 2` and 3 instances, rounding down gives each instance
a share of 0, a complete outage for that client caused by *our* Redis problem. Rounding up
over-admits by at most `instances − 1` per window across the fleet.

**Known limits during an outage:**
- The share assumes traffic is spread evenly. If a load balancer pins one client to one
  instance, that client gets only 1/N of its limit until Redis returns.
- A single request larger than one instance's token share is refused as too large.
- `instances` is static config. If the fleet scales, it must be updated (or discovered).

**Tested:**
- `FallbackRateLimiterTest` (unit): falls back to the share, doesn't call Redis on every
  request while degraded, sends one probe per interval, recovers, reconciles in the right
  place, never throws from reconcile, and doesn't hide bugs.
- `PausedRedisFallbackTest`: a real Redis container is *paused* mid-test (the connection
  stays open but nothing answers, the realistic "Redis hangs" case). Requests are still
  answered in under a second, are limited to the local share, and go back to Redis after
  unpausing. Needs Docker, so it runs in CI.
- `TwoGatewayInstancesTest`: two complete gateway applications share one Redis. 200
  parallel HTTP requests, alternating between them, against a 50-per-minute client must
  give exactly 50 × 200 and 150 × 429.
