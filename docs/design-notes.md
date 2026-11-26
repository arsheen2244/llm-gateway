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
- **A timeout means "outcome unknown", not "failed".** Found by the paused-Redis test:
  the `acquire` that timed out while Redis was paused was still sitting in Redis's socket
  buffer, and Redis ran it on resuming. That request is then counted twice: locally (where
  it was admitted) and in Redis. The error is on the strict side (one slot fewer for the
  client) and happens at most once per instance per outage, because after the first
  timeout the fallback stops calling Redis until the next probe. Preventing it would need
  idempotent, cancellable requests, a lot of machinery to save one slot.

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

## 23. Three instances behind nginx (Docker Compose)

**Chosen:** `docker compose up` starts nginx on port 8080, three gateway instances
(`gateway-1/2/3`, defined once through a YAML anchor), one Redis and the mock. The
gateways run the `local` profile, use `backend=redis` (switchable with
`RATE_LIMIT_BACKEND=in-memory` for the baseline), and set `instances=3` so the Redis-down
fallback enforces a third of each limit per instance.

**nginx settings that matter for an LLM gateway:**
- `proxy_buffering off`: otherwise nginx collects an SSE stream and delivers it in lumps,
  and streaming is gone. (The gateway also sends `X-Accel-Buffering: no` for the same
  reason.)
- `proxy_http_version 1.1` + an empty `Connection` header + `keepalive 64`: reuse
  connections to the gateways instead of a TCP handshake per request.
- `proxy_read_timeout 300s`: streams can run for minutes; only silence should end them.
- **No retries of POSTs:** nginx's default `proxy_next_upstream` doesn't resend
  non-idempotent requests to another instance. That is what we want, because a resent LLM
  call costs money twice. Whether and when to retry is week 3's decision, in the gateway.
- Round robin (the default). *Rejected:* `least_conn`, which helps when request costs
  vary a lot, but makes the even spread across instances (which the rate-limit drift
  measurement assumes) harder to reason about.

**Named services vs `replicas: 3`:** with replicas, nginx would resolve one service name to
several IPs once at startup, and an instance replaced later would be missed. Explicit
services make the upstream list readable, and `X-Upstream` names the instance that served
each request.

**`X-Upstream` header:** nginx adds the address of the instance that answered. The smoke
test uses it to prove traffic reaches more than one instance. It exposes internal
addresses, which is acceptable for a local demo; production would drop it.

**Startup order:** Redis has a health check and the gateways wait for it. nginx can come
up before the gateways are ready and answers 502 meanwhile, so the smoke test waits for a
real 200 (it previously accepted any HTTP answer, which a 502 from nginx would have passed).

## 24. Measured drift: shared Redis vs per-instance limiting

**Method** (`load-test/rate-limit-drift.js`, workflow `rate-limit-drift.yml`):
- Docker Compose with nginx (round robin), three gateway instances, Redis and the mock
  (100 ms latency), on a GitHub-hosted runner. Each backend ran on **its own runner**.
- Two demo clients, each with one binding limit: `drift-requests` (300 requests/min) and
  `drift-tokens` (3,000 tokens/min; requests use `max_tokens: 100`, so each reserves 103
  and actually uses 13).
- k6 offered **25 requests/s per client (5× the request limit)** for 3 minutes, at a
  constant arrival rate. The offer must exceed the worst case (3 instances × the limit), or
  per-instance limiting could not show its full overshoot.
- `drift_report.py` records every admitted request (and its actual tokens) with its time,
  slides a 60 s window across the run one second at a time, and reports the **maximum
  admitted in any 60 s window**. That is exactly what "N per minute" promises.
- Any response other than 200 or 429 fails the run, so an invalid run can't produce
  numbers. Both runs passed this check.

**Results (2026-11-17, run 44006027643, commit `2b332b9`):**

| Backend | Client | Limit | Max in any 60 s | Drift | Mean per 60 s |
|---|---|---|---|---|---|
| Redis (shared) | requests | 300 | 300 | +0.0% | 299.7 |
| Redis (shared) | tokens | 3,000 | 2,899 | −3.4% | 2,853.7 |
| In-memory (per instance) | requests | 300 | 900 | +200.0% | 884.5 |
| In-memory (per instance) | tokens | 3,000 | 8,697 | +189.9% | 8,436.4 |

**What it shows:**
- **Shared Redis holds the request limit exactly** across three instances: not one rolling
  minute above 300.
- **Per-instance limiting admits N× the limit** (here 3×), because each instance grants a
  full limit of its own. This is why the shared limiter exists.
- **Tokens err on the strict side with Redis (−3.4%).** While a request is in flight, the
  window holds its *estimate* (103), not its usage (13). In-flight reservations therefore
  briefly use budget that reconciling later gives back. This is the price of reserving
  before the call, and it errs in the safe direction.
- **Admissions come in bursts.** With 5× the limit on offer, a sliding log admits the
  whole minute's allowance within seconds, then refuses until entries expire. A token
  bucket would spread the same allowance evenly. Choosing between them depends on whether
  clients or providers mind bursts.

**Limits of the measurement:**
- Admission times are k6's response times, not Redis's admit times. They differ by
  roughly the request latency (~100 ms), so a window can be shifted slightly.
- Windows only cover the span between the first and last admitted request, which is why
  there are 72–99 windows rather than ~120. The *maximum* is unaffected; the means slightly
  favour busy periods.
- One run per backend on a shared CI runner: the request-limit result is structural (exact
  either way), but the token percentages will vary a little between runs.

## 25. Failover lists in routing

**Chosen:** a route names either one provider (`provider: openai`, the pre-week-3 form,
still valid) or an ordered list:

```yaml
- model-pattern: "gpt-*"
  providers:
    - name: openai
    - name: anthropic
      model: claude-sonnet-4-5
```

`ModelRouter.targets(model)` returns the list as `ProviderTarget`s (provider + optional
model override), in order. The resilient executor (note 27) walks it.

**Why a per-entry model override:** failing over between providers of the *same* protocol
(two OpenAI-compatible endpoints) can keep the model name. Failing over *across* protocols
cannot, because `gpt-4o` doesn't exist at Anthropic. The override says which model stands in.
The client's request (and the model name the rate limiter, logs and later the cache see)
is unchanged; only the call to that provider uses the override.

**Checked at startup:** each route sets exactly one of the two forms, and every entry names
a configured provider.

**Rejected:**
- *Separate "fallback" config per provider* (provider A falls back to B): it hides the
  order inside provider definitions and makes per-model differences impossible.
- *Weighted load balancing across providers:* useful for spreading cost, but a different
  feature. Failover is about order of preference, not about splitting traffic.

## 26. Deadlines on every provider call, and cancelling a call in flight

**Five limits on one provider call:**

| Limit | Default | Enforced by | Applies to |
|---|---|---|---|
| connect | 5 s | Apache HttpClient | every call |
| idle (silence between bytes) | 60 s | Apache socket timeout (note 16) | every call |
| first byte (response headers) | 60 s | our timer | every call |
| total | 120 s | our timer | non-streaming calls |
| max stream duration | 10 min | our timer | streams, instead of total |

**Why streams don't get the total:** a long, healthy answer streams for minutes. Its
real health signals are "did it start?" (first byte) and "is it still moving?" (idle).
A separate, generous maximum only guards against a stream that trickles forever.

**How a timer stops a call:** Spring's `RestClient` hides Apache's request object, so
`CancellableRequestFactory` (a subclass of Spring's Apache factory, overriding its protected
`createHttpUriRequest`) binds each request to the current call's `CallControl`, passed via
a thread-local because the request is created on the calling thread. `CallControl.stop()`
calls Apache's `cancel()`, which aborts the socket, so the thread blocked reading fails at
once. A `RestClient` interceptor marks the moment response headers arrive, which disarms the
first-byte timer. `ProviderCaller` runs the call, arms and disarms the timers (one shared
scheduler thread, since timer tasks only flip a flag and abort a socket), and reports a
deadline-caused failure as `TIMEOUT`, saying which deadline fired (so it maps to 504 and is
retryable). A stream that had already started keeps `STREAM_INTERRUPTED`, because the
client has data.

The same `CallControl` is how a losing hedged request will be cancelled (note 30).

**Rejected:**
- *`Future.cancel(true)` / thread interrupts:* blocking socket reads don't respond to
  interrupts, so the thread would keep waiting.
- *Apache's `RequestConfig.responseTimeout` for the first byte:* in the classic client it
  becomes the socket timeout for the whole exchange, so it would also cap the silence
  between stream chunks, conflating two different limits.
- *Moving the adapters from `RestClient` to Apache's API directly:* would work, but means
  rewriting every adapter's HTTP code, for one hook that Spring already offers.

**Tested** (`CallDeadlinesTest`, slow local server, real client):
- no headers within 500 ms gives `TIMEOUT` in under 1.5 s (the server would wait 3 s);
- a body trickling for 3 s after fast headers hits the 1 s total;
- a 1.2 s stream survives a 1 s total (under the 1.5 s stream maximum), while a 3 s
  stream is interrupted;
- cancelling from another thread ends a stream in under 1 s.

## 28. Circuit breaker: our own state machine (settings chosen by the owner)

**Settings** (`CircuitBreakerConfig.defaults()`): open when **≥ 50%** of calls failed in the
**last 30 s** *and* there were **≥ 20 calls** in that window; stay open **30 s**; then
**HALF_OPEN** allows **3 trial calls**: all 3 succeeding closes it (with an empty window),
any failure reopens it for another 30 s.

**Why a failure *rate* over a *time* window (owner's choice):**
- *vs consecutive failures:* at high traffic a provider failing 40% of calls rarely
  produces 5 failures in a row, so a count of consecutive failures would never trip; at
  low traffic 5 unlucky errors would.
- *vs the last N calls:* at low traffic "the last 20 calls" can be minutes old, so a
  recovered provider keeps looking bad. A time window forgets on a schedule.
- The **minimum calls** guard stops 2 failures out of 3 calls from counting as "67% failing".

**What counts:** timeouts, connection errors, 5xx/529 and provider 429 are *failures*
(the provider is unhealthy for us). Client errors such as a 400, and hedged calls we
cancelled, are *ignored*: they say nothing about the provider's health. An ignored result
also frees a half-open trial slot.

**OPEN → HALF_OPEN happens lazily:** on the first `tryAcquirePermission()` after the open
period, not by a background timer. No thread is needed, and a provider that nobody calls
doesn't need to be probed.

**How it differs from Resilience4j** (deliberately written ourselves, as an exercise in
explaining every line):

| | Resilience4j | Ours |
|---|---|---|
| Window | count-based (default) or time-based, configurable | time-based only |
| Half-open decision | failure *rate* over the permitted trial calls must be below the threshold | *all* trial calls must succeed (simpler and stricter) |
| Slow calls | can open on a slow-call rate as well as failures | no; slowness is handled by deadlines (note 26) and hedging |
| Open → half-open | after the wait, optionally by a scheduler (`automaticTransitionFromOpenToHalfOpenEnabled`) | only on the next request |
| Wait in open state | fixed or a function (e.g. exponential) of attempts | fixed |
| Classifying exceptions | `recordExceptions`, `ignoreExceptions`, predicates | the caller decides: `onFailure` / `onIgnored` |
| Extras | event publisher, metrics, `DISABLED` / `FORCED_OPEN` states, registry | none (logging is done by the caller) |

The trade-off: Resilience4j is battle-tested and far more configurable. Ours is a few dozen
lines whose every behaviour is pinned by `CircuitBreakerTest`, with no dependency, which
suits a project whose goal is to explain each mechanism.

**Implementation:** `SlidingWindowCircuitBreaker`; the owner first planned to write it, then
asked for it to be written for them, so note 29 explains it step by step.

## 29. How `SlidingWindowCircuitBreaker` works

**What it remembers:** the current `state`; in CLOSED, a deque of recent outcomes
(`(time, failed)`, oldest first) plus a running `failures` count; in OPEN, `openedAt`; in
HALF_OPEN, `trialsInUse` (trial calls still running) and `trialSuccesses`.

**Each method:**
- `tryAcquirePermission()`: CLOSED → yes. OPEN → no, unless `openDuration` has passed, in
  which case it switches to HALF_OPEN and this caller becomes trial #1. HALF_OPEN → yes while
  `trialsInUse + trialSuccesses < 3`, which takes a slot.
- `onSuccess()`: CLOSED → record a success. HALF_OPEN → move one trial from "in use" to
  "succeeded"; the third success closes the breaker with an empty window. OPEN → ignore.
- `onFailure()`: CLOSED → record a failure, then open if `calls ≥ 20` and
  `failures / calls ≥ 0.5`. HALF_OPEN → open again. OPEN → ignore, so a late failure can't
  extend the open period.
- `onIgnored()`: HALF_OPEN → give the trial slot back; otherwise nothing.

**Recording** appends the outcome, then drops outcomes older than the window from the front
of the deque, adjusting the failure count as they go. The rate is
`(double) failures / window.size()`; without the cast, integer division would make 10/20 equal
0 and the breaker would never open.

**Thread safety:** every public method is `synchronized`. The dangerous sequences are
check-then-act ("is a trial slot free? then take it"), and a lock around the whole method
makes each one atomic. That is what lets 100 simultaneous callers in HALF_OPEN get exactly 3
slots. Note 2 avoids `synchronized` on virtual threads only around blocking I/O (which pins
the carrier thread); these methods do no I/O and hold the lock for microseconds.
*Rejected:* lock-free atomics. Several fields change together (state, counters, the window),
so compare-and-set on each separately would let them disagree; a single lock is simpler and
fast enough here.

**Memory, a known trade-off:** the window stores every outcome of the last 30 s, so at
1,000 calls/s to one provider it holds ~30,000 small entries. *Rejected for now:* fixed time
buckets (e.g. 30 one-second buckets of counts), which use constant memory but round the
window's edge to a bucket boundary. Buckets would be the next step at high traffic.

**Opening clears the window, and so does closing.** Old failures must not count against a
provider that has just proven itself with three successful trials.

## 27. Failover, retries and the retry budget (`ResilientExecutor`)

**Where it runs:** the executor is the end of the interceptor chain, after rate limiting.
One client request is therefore one rate-limit reservation, however many provider calls it
takes, and the reservation is reconciled with the usage of the attempt that answered.

**The loop:**

```
for each provider in the model's list (note 25):
    no API key                        -> skip
    for attempt 1..maxAttemptsPerProvider (default 2):
        attempt > 1: retry budget empty -> stop retrying this provider
                     else wait random(0, min(2 s, 100 ms x 2^(retry-1)))
        breaker refuses (note 28)     -> next provider
        call it (with deadlines, note 26)
          success                     -> return
          client error 400/404/413/422 -> return it now
          stream already reached client -> return stream_interrupted, no failover
          retryable (429, 5xx, timeout, I/O) -> next attempt
          otherwise (e.g. 401/403: our key) -> next provider
all exhausted -> the last real failure, or 503 provider_circuit_open if every breaker refused
```

**Decisions and why:**
- **Client errors are not retried or failed over.** A malformed request gets the same answer
  from every provider; retrying it only adds load and latency.
- **401/403 fail over without retrying.** The *gateway's* key for that provider is wrong,
  so retrying won't help, but another provider may work.
- **Retry, then fail over.** One retry absorbs a blip (a single 503); a provider that fails
  twice is treated as down for this request, and the breaker takes over across requests.
- **Full jitter** (`random(0, ceiling)` rather than `ceiling ± a bit`): after an outage, many
  requests fail at the same moment. Without randomness they would retry at the same moment
  too, in waves that can knock a recovering provider over again.
- **Retry budget, per provider:** over a sliding 10 s window, retries are allowed while
  `retries < 1/s × 10 s + 10% × first attempts` (the approach of Finagle and gRPC). In a
  total outage, retries add at most about 10% on top of normal traffic instead of doubling
  it. The small floor lets a provider with little traffic still retry at all. Counts are kept
  in ten one-second buckets, so memory is fixed.
- **What feeds the breaker:** timeouts, I/O errors, unparseable responses, broken streams,
  5xx and 429 count as failures. Client errors and our own credential problems are reported
  as *ignored*, and so is anything that isn't a provider failure (a client disconnect), so
  breaker permissions are always released.
- **Streams fail over only before the first byte.** The executor wraps the sink and records
  whether any chunk has been handed on towards the client. Before that, a stream is just a
  request and can be retried or failed over freely. After it, the client has part of an
  answer, so the result is the clean `stream_interrupted` event from note 12.
- **Breaker state changes are logged** (WARN, with the provider), and the access log gains an
  `attempts` field, so a request that needed failover is visible.

**Tests:**
- `ResilientExecutorTest` (scripted fake providers, fake clock, sleeps recorded): retry then
  success; failover with the backup's model name; no retry or failover for a 400;
  401 → failover without retry; an open breaker is skipped without calling it; all
  failing → the last real error, with a bounded number of calls; every breaker open → 503;
  an exhausted budget means failover without retrying; providers without a key are skipped;
  streams fail over before the first chunk but never after; client errors don't feed the
  breaker; a client disconnect passes straight through.
- `FaultInjectionIntegrationTest` (real gateway, several real mock instances): a full primary
  outage (every request succeeds, and the dead provider gets a handful of calls instead of
  60), a flapping primary, all providers down (a clean 502/503 and at most 2 calls per provider
  per request), and streaming before and after the first byte.

**Effect on existing behaviour:** a retryable error from a single-provider route is now tried
twice before the client sees it. The status codes are unchanged.

## 30. Hedged requests (non-streaming)

**What it does:** if a non-streaming call hasn't answered within the hedge delay, the gateway
sends the same request again and uses whichever answer comes first; the other call is
cancelled through its `CallControl` (note 26), which aborts its socket.

**Hedge delay (owner's choice: observed latency):** the **p95** of recent successful call
latencies for that provider + model (the last 200 calls, kept in a ring buffer), never below
a **300 ms floor**, and a fixed **2 s default** until 20 samples exist. This is the
"tail at scale" idea: with the delay at p95, about 5% of calls are slow enough to hedge,
and those are the calls that would otherwise make up the tail.
*Rejected:* a fixed delay per route. It is simpler and predictable, but has to be retuned by
hand whenever a provider speeds up or slows down.

**Limits:**
- **Hedge budget:** per provider, hedges for at most **5%** of requests over a 10 s window
  (the same mechanism as the retry budget, without a per-second floor). A provider that
  becomes uniformly slow can't make the gateway double its traffic.
- **Only while the breaker is CLOSED.** A provider that is open or half-open (recovering)
  gets no extra load.
- **Non-streaming only:** a stream's "answer" is a sequence of chunks already being relayed,
  and two competing streams can't be merged.
- **Hedge to the same provider.** The tail latency of an LLM call usually comes from the
  request (queueing, a slow replica), not the provider as a whole, so a second try at the
  same provider often lands somewhere faster. Failing over to *another* provider is the
  executor's job when a call fails (note 27).

**Breaker accounting:** the hedge is part of the same attempt, not a separate vote. The
breaker sees one result: the winner's success, or the primary's failure if both fail.

**Threads and logging:** the primary (and the hedge) run on virtual threads while the request
thread waits for the first success (`CompletableFuture`s, with a small "first success or both
failed" combiner). The request's MDC (request ID, client, model) is copied to those threads,
which closes the follow-up from note 14. A hedged request is logged and marked `hedged=true`
in the access log.

**Known costs and biases:**
- **A cancelled LLM request may still be billed.** The provider may already have generated
  tokens we never read. Hedging trades money for tail latency, which is one more reason for
  the 5% cap.
- **Survivorship bias:** the cancelled loser contributes no latency sample, so the recorded
  p95 can drift slightly low. The floor limits how low the delay can go.
- **LLM latency depends on output length,** so one p95 per provider + model mixes short and
  long completions. A finer key (e.g. by `max_tokens` bucket) is a possible refinement.
- Every non-streaming call now runs on a second virtual thread (so the request thread can
  wait with a timeout). That costs microseconds, which is negligible next to an LLM call.

**Tests:**
- `HedgerTest` (fake calls with controlled timing): a fast call isn't hedged; a slow primary
  is hedged, the hedge wins and the primary's `CallControl` is cancelled; a primary that
  answers first still wins and cancels the hedge; when both fail the primary's error is
  reported; no hedge when the budget is spent or the breaker isn't closed; the delay moves
  from the default to max(floor, p95) after enough samples; the call sees the caller's MDC.
- `LatencyTrackerTest`: percentiles, and old samples being overwritten.
- `HedgingIntegrationTest` (a mock that is always slow): a hedge really reaches the provider
  (2 requests for 1 client call) and the client gets one answer; streams are never hedged.

## 31. Measured resilience (week 3)

**Method** (`load-test/resilience.js`, workflow `resilience-measure.yml`): the normal Compose
stack (nginx, three gateways, Redis) plus three mocks, each with a base latency of 100 ms:
`mock-down` answers 500 to everything, `mock-backup` is healthy, and `mock-slow` makes **3%**
of calls **2 s** slower. k6 offers a constant **20 requests/s** (90 s per outage phase, 180 s
for the slow provider). Latency percentiles are of *successful* requests. Extra volume is
counted at the mocks' `/stats` (before and after each phase), not estimated by the gateway.
Each scenario ran on its own GitHub-hosted runner.

**Results (2026-11-17, run 44021942824, commit `eec4188`):**

| Scenario | Requests | Success | p50 | p95 | p99 | Provider calls per client request |
|---|---|---|---|---|---|---|
| Primary down, route [down → backup] | 1,801 | **100.00%** | 107 ms | 111 ms | 367 ms | 1.038 (down 69, backup 1,801) |
| Primary down, route [down] only | 1,800 | 0.00% | – | – | – | 0.005 (down 9) |
| Slow provider, hedging on | 3,601 | 100.00% | 106 ms | 113 ms | **410 ms** | 1.024 |
| Slow provider, hedging off | 3,600 | 100.00% | 106 ms | 112 ms | **2,107 ms** | 1.000 |

**What it shows:**
- **Failover keeps a full outage invisible to clients:** 100% success. p50/p95 are the backup's
  own latency; the p99 (367 ms) is the early requests that also paid for the failed primary
  attempts (and their backoff) before the breakers opened.
- **The breaker protects the dead provider:** 69 calls in 90 s instead of up to 3,600 (two
  attempts per request). What remains is the calls that tripped each instance's breaker, plus
  one half-open trial every 30 s per instance. Retries added only about 4% (1.038 calls per
  request), so the retry budget and breaker together kept an outage from multiplying load.
- **Without failover the breaker turns failures into fast 503s:** the dead provider saw 9
  calls for 1,800 requests. (The report script's "extra volume −99.5%" wording is misleading
  here. It means "far fewer upstream calls than client requests", because the breaker
  answered most requests itself.)
- **Hedging cuts p99 from 2,107 ms to 410 ms for 2.4% more provider calls.** 410 ms is about
  the 300 ms floor plus one fast (~106 ms) call. The fast 3% got hedged within the 5% budget.
  The **max is ~2.1 s either way**: a few slow calls were not rescued, because the budget was
  momentarily spent or both primary and hedge were slow.

**Limits of the measurement:**
- The mocks are synthetic: fixed 100 ms latency and a clean 3% tail. Real LLM latency varies
  with output length, so the p95-based hedge delay is noisier in practice (note 30).
- Each scenario ran once on a shared CI runner. The structural results (100% vs 0%, hedges
  ≈ the slow fraction) are robust; exact milliseconds will vary between runs.
- With three instances, each has its own breaker, latency history and budgets, so the
  numbers include three warm-ups and three sets of half-open trials.

## 32. Semantic cache: what is cacheable, and what counts as "the same request"

**Owner's choices for week 4:** embeddings from a local embedding service shared by all
instances (note 34), cached answers and vectors in Redis with vector search (note 35), and
the eligibility rule below. How answers are served and stored is note 33; the similarity
threshold is chosen in note 36.

**Eligibility:**
- **Exact layer:** the request explicitly sets `temperature` ≤ 0.3. An unset temperature
  means the provider's default (1.0 at OpenAI), so the caller didn't ask for a stable
  answer, and serving every caller the same text would change their results.
- **Semantic layer:** additionally **single-turn**: optional system messages, then exactly one
  user message. Two conversations can end with the same question after very different
  context, so matching whole conversations "by meaning" is far riskier than matching one
  question.
- Streaming requests are eligible; a hit is replayed as SSE.

**Scope (`CacheKeys.scope`):** a hash of client id, model, system prompt and sampling
settings (`temperature`, `top_p`, `max_tokens`, `stop`). Lookups only ever happen inside one
scope, so **one client never receives another client's answer**, and an answer generated
under one system prompt or temperature is never served under another. The client id stands
for the API key: they map one-to-one (note 17), and the id is not a secret.

**Normalization** (for the exact key, and for the text that is embedded): trim, collapse every
run of whitespace to one space, Unicode NFC (so an accented character typed two different ways
compares equal). **Case is kept:** "US" and "us" can mean different things, and a wrong hit is
worse than a miss. The exact key hashes the scope plus every non-system message with a length
prefix, so `("ab","c")` and `("a","bc")` can't collide. `stream` and `stream_options` are not
part of any key, because the same answer can be replayed either way.

**Rejected:**
- *Lower-casing or stripping punctuation for the exact key:* more hits, but
  "Let's eat, Grandma" and "Let's eat Grandma" stop being different. Fuzzy matching is the
  semantic layer's job, and that layer is measured.
- *Caching every request with an opt-out header:* highest hit rate, but it silently returns
  identical text to callers who asked for variety (high temperature).

## 33. Serving and storing cached answers (exact layer)

**Where:** `CacheInterceptor`, in the interceptor chain right **after rate limiting** (order 200,
behind the limiter's 100, both declared through Spring's `Ordered`). A cache hit still counts
as a request against the client's limit, but the limiter sees `X-Cache: exact|semantic` and
**refunds the reserved tokens**, since no provider tokens were spent. The response still carries
the original call's `usage`, so clients see the normal OpenAI shape.

**`X-Cache` header:**
- `exact` (or `semantic`): answered from the cache; no provider was called.
- `miss`: the cache was checked and had nothing.
- `bypass`: the cache was not used, because the client sent `Cache-Control: no-cache`/`no-store`
  or the request isn't eligible (note 32). This is one value beyond the brief's three: it
  separates "checked, nothing there" from "not checked at all", which hit-rate numbers need.

**What gets stored:** only complete answers: `finish_reason` `stop`, or `length` (cut at
`max_tokens`, which is part of the scope, so it is reproducible). Failed calls and broken
streams are never stored.

**Streaming:**
- *Miss:* the chunks pass through to the client untouched while a collector rebuilds the full
  text, and it is stored only if the stream completes.
- *Hit:* the cached answer is replayed the way a provider streams: a role chunk, the text in
  word-sized pieces, a finish chunk, then the usage chunk (forwarded only if the client asked
  for it, note 12).
- An answer cached from a stream serves a normal request, and vice versa: the key ignores
  `stream` (note 32).

**Stores (`CacheStore`):** `in-memory` (the default; single instance and tests; lazy expiry and a
10,000-entry cap) and `redis` (each answer is one JSON string under `cache:exact:<key>` with a
Redis TTL, shared by every instance). Docker Compose uses Redis. The cache must never fail a
request: store errors are logged and treated as misses.
*Rejected:* an LRU eviction policy for the in-memory store; with several instances the Redis
store is the right tool.

## 34. Embeddings: a local service shared by all instances

**Owner's choice:** a separate **embedding service container** rather than a model loaded
inside each gateway JVM. Hugging Face **text-embeddings-inference (TEI)** on CPU, running
**`BAAI/bge-small-en-v1.5`**: 384 dimensions, ~130 MB, English, near the top of its size class
on retrieval and similarity benchmarks. It runs locally, so tests and benchmarks cost nothing.

| Option | For | Against |
|---|---|---|
| **Service container (chosen)** | One model in memory for all 3 instances; the gateway stays a small JVM; the eval script calls exactly the same service, so measured thresholds apply to production | One more container and network hop |
| Model inside the JVM (ONNX Runtime / DJL) | No hop | ~130 MB model + native runtime in every instance; Java inference libraries add weight; the Python eval would need a second, possibly different, implementation |
| Hosted embedding API | No ops | Costs money per call, sends prompts to a third party, and tests would need a key |

**Client (`TeiEmbedder`):** `POST /embed` with `normalize: true` (unit-length vectors, so
cosine similarity is a dot product) and **`truncate: false`**. The model reads at most 512
tokens; silently cutting longer prompts would make two prompts that differ only near the end
look identical, which would serve a wrong answer. Too long is an error, so the semantic layer is
skipped for that request. Every answer is checked to be exactly 384 numbers, the length the
vector index (note 35) is built for.

**Never slow down or fail the request:** a **500 ms** connect/read timeout, no automatic retries,
and its **own small connection pool** (50), separate from the providers' pool (note 16), so a
stuck embedding service can't use up provider connections. Any failure is an
`EmbeddingException`, which the cache treats as a semantic miss. In Compose the gateways start
without waiting for the model to load, for the same reason.

**Checked in CI:** the Compose smoke test runs `scripts/check-embeddings.sh`, which waits for the
model, checks the vector length and prints the similarity of a paraphrase pair and an unrelated
pair (a sanity check, not a measurement; the threshold comes from the labelled evaluation).

*Not done:* pinning the model to a specific Hugging Face revision; the image tag is pinned
(`cpu-1.8.1`), the model files are not.

## 35. Semantic layer: Redis vector search

**Owner's choice:** store prompt embeddings in **Redis with vector search**, the Redis that
already holds rate limits and exact entries. Compose and the Redis tests move from
`redis:7.4-alpine` to **`redis:8.2`**, which has the query engine (search and vectors) built in.
*Rejected:* a separate vector database (pgvector, Qdrant): one more service to run and keep
in sync, for a cache that holds thousands of entries per scope, not millions.

**Lookup order:** exact key first (one `GET`), then, only for single-turn requests (note 32):
1. embed the normalized user message (note 34);
2. search **within the request's scope** (client, model, system prompt, sampling
   parameters) for the nearest cached prompt;
3. hit if cosine similarity ≥ `gateway.cache.similarity-threshold`: `X-Cache: semantic` plus
   **`X-Cache-Similarity`** (e.g. `0.9612`), so a client or the evaluation can see how close the
   match was.

On a miss, the answer is stored in **both** layers, reusing the embedding from the lookup (one
embedding call per request at most). A semantic miss costs one embedding call plus one search
before the provider call; that overhead goes into the week-5 load test, not guessed here.

**Storage:** each answer is a Redis hash `cache:sem:<uuid>` with `scope` (TAG), `vector`
(384 float32, little-endian) and `answer` (JSON), plus a TTL. The index:

```
FT.CREATE cache-semantic ON HASH PREFIX 1 cache:sem:
  SCHEMA scope TAG  vector VECTOR FLAT 6 TYPE FLOAT32 DIM 384 DISTANCE_METRIC COSINE
```

- **Scope as a TAG filter inside the KNN query** (`(@scope:{…})=>[KNN 1 @vector $vec]`), so the
  search only ever considers this client's entries for this model, system prompt and settings.
  One client can never receive another's answer, even at similarity 1.0.
- **FLAT (exact) rather than HNSW (approximate):** a scope holds thousands of entries, where an
  exact scan is fast, and exact search means production finds the same nearest neighbour the
  threshold evaluation found. HNSW is the switch if scopes grow to hundreds of thousands.
- **The index is created on first use**, by whichever instance gets there first ("already
  exists" is ignored), so a gateway can start while Redis is down.
- **Expiry:** Redis TTLs on the hashes; expired hashes leave the index on their own.
- **The search runs inside a small Lua script** (`cache/semantic_search.lua`). Not for
  atomicity: inside a script the reply always has the classic RESP2 shape, whatever protocol
  the client connection negotiated (Lettuce prefers RESP3, where `FT.SEARCH` replies are maps).
  Redis reports cosine *distance*; similarity = 1 − distance.

**In-memory store:** the same behaviour with a linear scan of the scope's entries, for a single
instance and the unit tests.

**Threshold:** `0.95` is a placeholder; note 36 chooses it from the labelled evaluation.

## 36. Similarity threshold: none is safe, so the semantic layer ships off

**Measured** on 2026-11-18 (GitHub Actions run 44356432771; files in
`results/cache-threshold/`): 200 labelled pairs (`eval/cache-pairs.json`), 100 paraphrases that
should hit and 100 look-alikes that must not, embedded with `BAAI/bge-small-en-v1.5` through
the same TEI service and text normalization as the gateway.

**About the labels:** Claude drafted them. The owner delegated the review to Claude, which
re-read every pair, reworded three ambiguous paraphrases and flipped no labels. They are one
careful pass, not an independent human review.

| Threshold | Hit rate (paraphrases served) | False-hit rate (wrong answers served) |
|---|---|---|
| 0.80 | 98% | 77% |
| 0.85 | 92% | 58% |
| 0.90 | 72% | 30% |
| 0.93 | 37% | 16% |
| 0.95 | 12% | 9% |
| 0.97 | 4% | 6% |
| 0.99 | 0% | 5% |

(Every threshold from 0.70 to 0.99 is in `results/cache-threshold/threshold-sweep.md`, with a plot.)

**No threshold works.** A cache that serves a wrong answer is worse than no cache, so the
target was a false-hit rate near zero. The look-alikes and the paraphrases overlap completely:

- **Direction swaps score higher than any paraphrase.** "Convert 5 miles to kilometers" vs
  "…5 kilometers to miles" is **0.992**; the scp upload vs download pair is **0.999**; Tokyo/London
  time, string↔integer, Celsius↔Fahrenheit and JSON↔XML are all ≥ 0.989. The model reads which
  words appear, barely their order.
- **Other look-alikes reach the paraphrase range:** a different language (reverse a list in
  Python vs JavaScript, 0.955), a different number (split $120 among 4 vs 5 people, 0.954), a
  different word that flips the answer (daylight saving *start* vs *end*, 0.951), negation
  (median 0.912).
- **Real paraphrases often score lower:** "How do I create a virtual environment in Python?"
  vs "What's the command to set up a Python venv?" is 0.752; typos ("hw do i learn pythn fast")
  0.805. The paraphrase median is about 0.92, which is also where negation pairs sit.

**Decision (owner's choice): the semantic layer stays in the code, switched off by default**
(`gateway.cache.semantic-enabled: false`). The exact layer (note 33) runs as before. The
embedding service moves to a Compose profile (`--profile semantic`) so the default stack
doesn't run a model nothing uses; CI still starts it, checks it, and runs the evaluation
pipeline. `similarity-threshold` stays at 0.95 only so the setting has a value; at 0.95 this
evaluation served 9 wrong answers for every 12 paraphrases.

*Rejected for now:*
- **A reranker** (a cross-encoder such as `bge-reranker-base` reading both prompts together,
  which does notice order and negation) as a second check on each candidate. Promising, but a
  second ~1 GB model, added latency on every miss, and a new labelled set would be needed to
  measure it fairly, since these 200 pairs were used to find the problem.
- **Rule-based guards** (numbers must match, negation words must match, same words in a
  different order means no match). Cheap, but written against exactly these look-alikes, so the
  same set can't measure them honestly.
- **A larger embedding model.** Still one vector per prompt, built the same way; nothing here
  suggests it would separate "A to B" from "B to A".

**Limits of this evaluation:** pairs are scored on their own (in the gateway the nearest of
all cached prompts in the scope wins, so more entries mean more chances of a false hit); 200
English, single-sentence prompts; one model.

## 37. Usage events: one per request, recorded at the front of the chain

**Where:** `UsageInterceptor`, order 50, ahead of rate limiting (100) and the cache (200). Being
first means it sees every outcome: answers from a provider or from the cache, rate-limit
refusals, provider failures, clients leaving mid-stream. Each request produces exactly one
`UsageEvent`, published after the request is done to a `UsageSink`. Step 1 ships a sink that
only logs at DEBUG; step 2 replaces it with Kafka (note 38).

**What an event holds:** event ID (a random UUID), client id, model as requested, provider, the
model that provider was sent (added in note 39), prompt and completion tokens, latency, cache outcome (`X-Cache`, or `none` if the request ended
before the cache ran), status (`SUCCESS`, `RATE_LIMITED`, `PROVIDER_ERROR`,
`CLIENT_DISCONNECTED`, `ERROR`), whether it was a stream, and when it finished.
- **Client id, never the API key.** The key is a secret; the id already identifies the caller,
  and `GET /v1/usage` looks usage up by the id the key maps to.
- **Event ID made in the gateway.** It is what makes the consumer idempotent (note 39): a
  redelivered event carries the same ID and is ignored.
- **Tokens, not cost.** The consumer prices events from its `model_prices` table, so prices live
  in one place and changing one needs no gateway redeploy. *Trade-off:* the brief lists cost as
  an event field; it is stored on each event's row instead, not carried in the Kafka message.
- **Which provider answered.** With failover (note 25) it can differ from the route's first
  provider. The executor records each attempt's provider in a per-request `CallTrace` carried by
  `ChatContext`; after a success, the latest attempt is the one that answered. Null when no
  provider was called (rate-limited, cache hit).
- **Tokens of a cache hit** are the cached answer's counts (what the client sees in `usage`),
  with no provider; the consumer charges cache hits nothing.
- **Streams** take tokens from the final usage chunk, which the gateway always requests from the
  provider (note 12), even when the client didn't ask for it.

**Never slows or fails a request:** publishing is wrapped so that a throwing sink is logged and
ignored, and the client still gets its answer or its original error. The sink is called on the
request thread, so it must return at once; the Kafka sink hands the event to the producer's
buffer and returns (note 38).

**Not recorded (limitations):**
- Requests rejected before the pipeline (bad API key, invalid body, unknown model, no provider
  configured): nothing was attempted, and no tokens were spent.
- Tokens of a stream that broke or was abandoned before the usage chunk: recorded as 0, though
  the provider may have billed part of it. (The rate limiter instead keeps its estimate in that
  case, note 19: for limits, over-counting is the safe side; for billing, an invented number
  is not.)

## 38. Usage events to Kafka: a bounded memory buffer when Kafka is down

**The rule from the brief:** sending usage must never slow or fail a request. **The owner's
choice for Kafka outages:** keep unsent events in a bounded memory buffer and send them when
Kafka is back; lose them only when the buffer fills or the instance dies, and count every loss.

**How (`KafkaUsageSink`):**
- `publish()` only offers the event to an `ArrayBlockingQueue` (100,000 events by default)
  and returns. A request never touches the Kafka client. Each queued event is a small object
  (a few hundred bytes, an estimate, not measured), so a full queue plus the 32 MB producer
  buffer stays well inside the 384 MB heap each Compose instance has.
- One background thread takes events from the queue and calls `producer.send()`. That call can
  block (no metadata yet, producer buffer full) for up to `max.block.ms` = 10 s; only this thread
  ever waits.
- A send that fails with a *retriable* error (timeout, broker unavailable) goes back into the
  queue and is sent again later. A *non-retriable* one (e.g. record too large) is dropped: sending
  it again would fail forever.
- Every loss increments `dropped()`, and is logged (the first, then every 1,000th). It is also the
  `usage_events_dropped_total` metric (note 42).

**Producer settings:** `acks=all` and idempotence on (once acknowledged, an event is durable,
and the producer's own retries never write it twice); `linger.ms=5` and lz4 compression (small
batches); a 32 MB producer buffer; `delivery.timeout.ms` 2 minutes, after which the send fails
and the event goes back into the queue.

**Duplicates are possible, on purpose.** A send that timed out may still have reached Kafka, and
re-queueing it sends it again. The consumer stores each event ID once (note 39), so this costs
nothing; a lost event, by contrast, could not be recovered. At-least-once delivery plus an
idempotent consumer gives exactly-once *totals*.

**Ordering:** events are keyed by client id, so one client's events share a partition. A
re-queued event goes to the back of the queue, so order is not strict; nothing depends on it,
since daily totals are sums.

**Shutdown:** Spring closes the sink after the web server has stopped taking requests (beans are
destroyed after the server's lifecycle stops). `close()` sends what is still queued for up to
`shutdown-timeout` (5 s), closes the producer, and counts anything left as dropped.

**Topic:** `usage-events`, created by the broker on first use with 3 partitions
(`KAFKA_NUM_PARTITIONS` in Compose), enough for up to 3 consumers. A real deployment would create
it explicitly with a replication factor of 3; Compose has one broker.

**Options considered (the owner chose the first):**

| Option | Kafka down for a while | Instance crash | Cost on the request path |
|---|---|---|---|
| **Memory buffer** (chosen) | Kept, up to the buffer size; then dropped and counted | Queued events lost | One queue offer |
| Outbox table in PostgreSQL | Kept | Kept | A database write per request; PostgreSQL becomes a dependency of serving |
| Drop | Lost and counted | Lost | One failed offer |

**What this means in practice:** short outages lose nothing; an outage long enough to fill
100,000 events per instance, or a crash, under-counts usage. Billing-grade accounting would
need the outbox (or a disk spool), at the cost above.

**Tests:** `KafkaUsageSinkTest` (Kafka's `MockProducer`: retry after a timeout, drop on a
permanent error, full queue never blocks, shutdown); `KafkaUsageSinkBrokerTest` (a real broker
in Testcontainers is paused: 200 events are published with no call taking 50 ms or more, and
all of them arrive after it is unpaused); `KafkaDownIntegrationTest` (the whole gateway with no
Kafka at all: requests succeed as usual).

## 39. The usage consumer: at-least-once delivery, exactly-once totals

**What it is:** `usage-consumer`, a separate service (the owner's choice). It scales and restarts
independently, and a slow database or a crashed consumer can't touch request serving.
*Rejected:* consuming inside each gateway; fewer services, but accounting would share CPU with
requests, and stopping one means stopping the other.

**Tables** (Flyway, `V1__usage.sql`): `usage_events` (one row per request, `event_id` is the
primary key), `usage_daily` (totals per client and UTC day), `model_prices`.

**Exactly-once totals.** Kafka may deliver an event more than once: the gateway re-sends after a
timeout (note 38), and the consumer re-reads whatever it had not committed when it crashed. So,
per event, in **one transaction**:
1. `INSERT INTO usage_events ... ON CONFLICT (event_id) DO NOTHING`;
2. only if that added a row, add the event to `usage_daily`.

The Kafka offset is committed **after** the database commit (`ack-mode: record`):

| Crash happens | What is left | After the restart |
|---|---|---|
| before the database commit | nothing (rolled back) | redelivered, recorded |
| after the database commit, before the offset commit | recorded | redelivered, the insert conflicts, ignored |
| after the offset commit | recorded | not redelivered |

Two consumers holding the same event at once (a slow one that lost its partition) are safe too:
the second insert waits for the first transaction, then conflicts.
*Rejected:* committing the offset first (a crash loses the event); Kafka transactions (they cover
Kafka topics, not a write to PostgreSQL); computing totals on every read with `SUM` over events
(simplest, and always right, but a billing read would get slower as events pile up).

**Errors:**
- *PostgreSQL down, hanging or misconfigured:* the same event is tried again, every second, for
  ever. Skipping it would lose it; waiting only adds delay, because Kafka keeps the events behind
  it. A 10 s socket timeout turns a hang into an error that can be retried.
- *The event itself is bad* (not a valid event, or PostgreSQL rejects its values): retrying can't
  help and would block the partition for ever, so it goes to `usage-events-dlt` unchanged and is
  logged. If that send fails, the event is tried again, not skipped. Example: a client may put a
  NUL character in the model name, which PostgreSQL cannot store. Retrying that would let one
  client stop accounting for everyone on the partition.

**Cost** is worked out here, in exact decimals (`numeric`, `BigDecimal`), from the price in force
when the event happened (`effective_from`), so re-reading old events after a price change gives the
old price. Storing the cost on the event's row means later price changes don't rewrite history.
- **Priced by provider and upstream model**, not the model the client asked for. After a failover
  the provider may be sent (and bill) another model (note 25). Step 1's event recorded only the
  requested model, which would have priced those requests wrongly; `upstreamModel` was added.
- **No price known:** `cost_usd` is NULL and the day's `unpriced_requests` goes up. Never a silent $0.
- **Cache hits** cost 0, and their tokens are counted in separate columns, so
  `prompt_tokens` and `completion_tokens` are what providers processed.
- Only made-up prices for the mock models are included (profile `local`). Real prices change and
  must be checked against the provider, so none are committed; until they are added, real models
  show up as unpriced.

**The event format** is `contracts/usage-event.json`. The gateway tests that it writes exactly that,
and the consumer that it reads it. Unknown fields are ignored, so the gateway can add one first.
*Rejected:* a shared Java module (both services would have to be rebuilt together) and a schema
registry (one more service, for one event type).

**Kafka settings that matter:** `auto-offset-reset: earliest` (the default, `latest`, skips whatever
was sent before the consumer first ran); 3 listener threads, one per partition.

**Limitations:**
- Kafka keeps events for 7 days by default; a consumer stopped for longer would lose some.
- One transaction per event is simple, but one client's events share a partition and so are
  recorded one at a time. If the consumer falls behind, recording a batch per transaction is the
  next step. Step 7's load test measures this.
- Only the attempt that answered is counted. Failed attempts, cancelled hedges (note 30) and
  streams that broke before their usage chunk may have been billed by the provider.
- Dead letters are kept but not replayed automatically.
- `usage_events` grows without bound; a real deployment would partition it by month.

**Tests:** `UsageListenerParseTest` and `ModelPriceTest` (no Docker); `UsageStoreTest` (PostgreSQL:
redelivery, four threads recording the same events, rollback when the totals can't be updated,
prices over time, UTC days); `UsageConsumerIntegrationTest` (Kafka and PostgreSQL: duplicates in
random order, dead letters, PostgreSQL paused for 15 s); `scripts/check-usage.sh` (in CI, the Compose
stack: 30 requests through nginx become exactly 30 priced events, and the totals match).

## 40. GET /v1/usage: the calling key's usage, from the daily totals

**What it returns:** requests (by outcome), cache hits, tokens and cost for the calling key, in total
and per UTC day. `from` and `to` are optional dates, both included; the default is the last 30
days and the limit 366. Days without usage are left out.

**Whose usage:** only the authenticated key decides. There is no client parameter, so one client
can't read another's numbers by changing the request.

**Reads `usage_daily`, not `usage_events`:** at most 366 small rows by primary key, however many
requests there were.

**Where the gateway's database use stops:**
- The connection pool is private to `JdbcUsageReader`, and Spring Boot's own `DataSource` is
  excluded. Nothing on the chat path can use the database, and the gateway starts without it.
- The pool is small (3), connects on first use, waits at most 2 s for a connection and 5 s for an
  answer. A slow or missing database gives `503 usage_unavailable`; chat is unaffected.
- It connects as `usage_reader`, which may only `SELECT` from `usage_daily`. Compose creates the role
  with the database, and the consumer's migration grants it that one table. A gateway that is
  broken into can't change or delete billing records.
- *Rejected:* putting the endpoint in the usage consumer. The gateway would then not need the
  database at all, but the consumer would need its own copy of API-key checking.

**Cost is decimal text** (`"cost_usd": "0.000966"`). JSON numbers are usually parsed as doubles, which
can't hold most decimal fractions exactly. `unpriced_requests` above 0 says the cost is incomplete.

**Not rate-limited:** it spends no tokens, and the tiny pool bounds the load on the database. A
client calling it in a loop can make other clients' usage calls slow or 503, but not chat.

**Freshness:** events arrive through Kafka, so a request normally shows up shortly after it ends, and
later if Kafka or the consumer is behind. The report is not a live counter to enforce limits with.

**Tests:** `UsageReportsTest` (dates, exact sums); `UsageEndpointIntegrationTest` (the JSON, another
client's usage can't be read, 401, 400); `UsageDatabaseDownIntegrationTest` (no database: 503 and chat
works); `JdbcUsageReaderTest` (PostgreSQL with the consumer's migrations: ranges, what the role may
not do, a paused database); `check-usage.sh` (in CI: the API's numbers equal the database's).

## 41. Reconciliation: totals stay exact when the consumer is killed

The claim in note 39 (nothing lost, nothing double-counted) is tested by killing the consumer, not
by arguing. Two tests, because each shows something the other can't:

**`ReconciliationTest`** (Kafka and PostgreSQL in containers, the consumer as its own JVM): 6,000
events of every kind over 12 clients and 2 days, 600 of them sent twice, in random order. The
consumer is killed with SIGKILL once a fifth is recorded, restarted, killed again past half, and
restarted. Then every event ID must be stored exactly once, and all 11 totals of every client and
day must equal sums the test makes itself, sharing no code with the store.
- **SIGKILL, not a stop.** A normal stop lets Spring commit its offsets, which hides the case that
  matters: recorded in PostgreSQL, not yet committed in Kafka.
- **Run twice, with `ack-mode` `record` (production) and `batch`.** With `record` the gap between the
  two commits is too short to hit on demand. `batch` commits after up to 500 events, and the test
  asserts that at the kill fewer offsets were committed than events recorded, so redelivery
  certainly happened. The totals are right because of the event-ID rule, not because the kill
  was lucky. It also shows the rule doesn't depend on the ack mode.
- **The kill must be partway:** the test fails if nothing or everything was recorded at that point.

**`scripts/reconcile_usage.py`** (CI, the whole Compose stack): 900 real requests through nginx and the
three gateways, half of them streams. The consumer is killed during the first 300, stays down for
the next 300 (which must all succeed, with nothing recorded) and is started again for the last 300.
What is expected comes from the clients' side: the number of answers, and the tokens reported in
them. PostgreSQL and `GET /v1/usage` must match exactly, cost included.

**What this does not show:** losses before Kafka. A gateway that crashes, or whose buffer overflows,
loses the events it had not sent (note 38), and those requests are then missing from the totals.
Kafka itself is never killed here; with one broker and no replication, losing its disk loses events.

## 42. Metrics: one request timer, three gauges, a provisioned dashboard

**One request metric, from the usage event.** `gateway_requests_seconds` is recorded from the same
`UsageEvent` that accounting uses (note 37), so request rate, latency, error rate, rejections and cache
hit rate come from one place and can't disagree with the bill. Tags: `status`, `provider`, `cache`,
`stream`.
- **Why not Spring's `http_server_requests`?** It sees HTTP statuses. A stream that breaks after it
  started is still a 200 there, and cache hits look like any other request. It is exposed too, but
  the dashboard doesn't use it.
- **The model is not a tag.** Clients choose it and routes end in `*`, so one client could create
  endless time series. Tokens and cost per model are in PostgreSQL, where that is no problem.
- **Histogram buckets, not percentiles.** Percentiles of three gateways can't be combined (an average of
  three p95s is not a p95); bucket counts can be added, and the percentile taken from the sum. The
  price is precision: a percentile is interpolated inside its bucket. Step 7's numbers come from k6,
  which measures each request, not from here.
- **Rejections and errors exist at 0 from the start.** A series that first appears at 5 shows no
  increase to Prometheus. Other combinations appear on first use, so a new provider's first
  request can be missing from a rate (never from the totals).

**Also:** `gateway_breaker_state` per provider (0 closed, 1 half-open, 2 open, so the maximum over
gateways is the worst), and `usage_events_sent/dropped_total` and `usage_events_queued` (note 38).

**A port of its own (9091).** Metrics name providers and show traffic, so they don't belong on the public
port, and nginx, which forwards everything, can't expose them. Only `/actuator/prometheus` is on.
Compose publishes Prometheus and Grafana to this machine only.
*Rejected:* the public port with a deny rule in nginx: one forgotten rule away from being public.

**Health checks are not exposed.** Spring's would report a gateway "down" when Redis is, although it keeps
serving (note 22), and a load balancer would then take all three out.

**The dashboard** is a file (`deploy/grafana/dashboards/llm-gateway.json`), loaded at startup and not
editable in the browser, so what is reviewed is what runs. Grafana's `$__rate_interval` keeps rates
right at every zoom. Error rate leaves rejections out of both sides; cache hit rate leaves bypassed
requests out, since the cache was never asked.

**Tests:** `RequestMetricsTest` (1,000 model names create no new series), `MetricsEndpointIntegrationTest`
(the endpoint of a real gateway: counts, breaker state, and 404 on the public port), and
`scripts/check_metrics.py` (CI, Compose): the counts in Prometheus must equal what the clients saw, and
every panel's query is read from the dashboard file and must return data. So a renamed metric
breaks the build, not the dashboard.

**Limitations:**
- The usage consumer exposes no metrics yet, so the dashboard can't show how far behind it is
  (`kafka-consumer-groups.sh --describe` can).
- No alerts are defined.
- With `gateway.usage.enabled=false` there are no request metrics either.
- Latency is recorded in whole milliseconds, so most cache hits show as 0 or 1 ms.

## 43. Full-stack load test: 1,000 requests per second, and what limits it

**Setup** (`load-test/full-stack.js`, `full_stack.sh`, workflow *Full-stack load test*): the whole
Compose stack with every feature on (nginx, three gateways, Redis rate limits and cache, usage events
through Kafka into PostgreSQL, Prometheus and Grafana), against the mock provider answering after
a fixed 100 ms. Everything, k6 included, shares one GitHub runner with 4 vCPUs.
- **Open model:** k6 starts requests at a fixed rate whatever the answers do. A closed model (a fixed
  number of virtual users) slows down with the system under test and hides how far behind it falls.
- **Each rate twice, one after the other:** 60 s straight to the mock, then 60 s through the gateways.
  *Added latency* is a percentile through the gateways minus the same percentile straight to the
  mock: a difference of percentiles, not a percentile of per-request differences.
- **A rate counts as sustained only with no failed request and no request k6 had to skip** (it
  ran out of virtual users because answers were too slow). Otherwise its latency isn't reported.
- **Every prompt is new, at temperature 0:** the cache looks each one up and stores its answer, its
  most expensive path. Non-streaming only.
- **Accounting is checked at every rate:** each answer carrying a gateway's `X-Request-Id` must appear
  in PostgreSQL. The script records how far behind the consumer was when the load stopped, and how
  long it took to catch up.
- **Three repetitions, each on its own runner**, because shared cloud machines vary (even the CPU model).

**Results** (run 45755495967; runs 2 and 3, both AMD EPYC 7763; run 1 failed in setup, see below;
raw data in `results/full-stack/`):

| Rate | Failed | Added p50 | Added p95 | Added p99 | Consumer behind at end |
|---|---|---|---|---|---|
| 100/s | 0 / 0 | +3.3 / +3.7 ms | +8.7 / +8.8 ms | +12.9 / +13.7 ms | 0 / 0 |
| 400/s | 0 / 0 | +4.3 / +4.2 ms | +19.5 / +21.7 ms | +45.8 / +47.9 ms | 0 / 0 |
| 800/s | 0 / 0 | +8.2 / +7.1 ms | +39.0 / +21.1 ms | +93.3 / +35.1 ms | 18,022 / 16,936 |
| 1,000/s | 0 / 0 | +15.2 / +15.3 ms | +53.7 / +55.5 ms | +93.5 / +84.7 ms | 34,608 / 34,565 |
| 1,200/s | 163 / 0 failed, 18,543 / 64 skipped | not sustained | | | |

- **Highest rate sustained: 1,000 requests per second** in both runs (60,000 and 60,001 requests,
  none failed). At 1,200/s, run 2 fell apart (p99 14 s, 18,543 requests skipped) and run 3 skipped 64;
  1,600 and 2,000 failed in both.
- **Nothing was lost.** At every rate, in both runs, every request a gateway answered was recorded
  in PostgreSQL exactly once. (At 1,600/s run 3 has 3 more events than answers: requests a gateway
  received after k6 had given up waiting for them.)
- **The usage consumer is the slowest part**, at 840–970 events per second while catching up
  (measured from 600/s up), close to the 1,000/s limit. Above roughly 600/s it falls behind during the load and catches up
  afterwards (36 s after a minute at 1,000/s). Requests are unaffected (that is the point of
  the queue, notes 38–39), but `/v1/usage` is late by that much. Batching inserts (note 39) is the
  next step if that matters.
- **Added latency grows with load,** because all of it shares 4 vCPUs. At 100/s the whole stack
  (nginx hop, auth, a Redis rate-limit script, a Redis cache lookup and store, usage event, metrics)
  adds about 3–4 ms at p50. Week 1's 1–2 ms was the gateway alone, with none of those.
- **Run to run, p50 agrees within about 1 ms; the tail varies more** (p99 at 800/s: 93 and 35 ms).
  Two runs on one CPU model are not enough for tight error bars; the table shows both, not an
  average.

**What was wrong at first, and fixed:**
- **nginx was the limit, not the gateway.** The first runs failed at about 1,000/s with connections
  closed and HTML 500 pages, which the gateways never send. nginx ran with its defaults: one worker
  process and 512 connections (counting those to the gateways), and it closed each client connection
  after 1,000 requests, while the client might already be sending the next one. Now: one worker per
  CPU, 8,192 connections each, and long-lived keep-alive.
- **The accounting check counted nginx's own errors as gateway answers.** It now counts only answers
  with `X-Request-Id`.
- **One run tells you little.** The first runs, on different CPU models, differed by 2–3x in tail latency.
- **Run 1 got a 502 from nginx before the measurement:** the smoke test had seen one gateway answer
  and moved on while another was still starting. It now waits for six 200s in a row.

**What this does not show:** real providers (whose seconds of latency dwarf these milliseconds),
streams, cache hits, the semantic layer (off, note 36), more than one machine, or anything about
what limits the stack at 1,200/s beyond the consumer's backlog. With every component on 4 shared
vCPUs, which one runs out first was not measured.

## 44. Out of heap: exit and restart, instead of running on half-dead

**What happened** (diagnostic run 45832600142, `scripts/diagnose_usage.py`): at 2,000 requests per
second, far past what the stack sustains (note 43), one gateway ran out of heap
(`OutOfMemoryError: Java heap space`). Each request runs on its own virtual thread and nothing
limited how many were in flight, so slow requests piled up in a 384 MB heap. The error killed
whichever threads happened to be allocating: Tomcat's acceptor and poller, and the Kafka producer's
I/O thread. The JVM itself kept running.
- **It still looked alive.** The container stayed "running", so Compose did nothing, and nginx
  kept sending it a third of the traffic, which then failed. That explains why every earlier run
  at 2,000/s failed almost exactly a third of its requests.
- **Usage events were lost without a trace.** Events the sink had already handed to the Kafka
  producer could never be sent, and no counter covers events inside the producer: they were neither
  sent, nor dropped, nor queued. This is the likely cause of the 13 events missing in an earlier
  run at 1,600/s.
- In the same workflow, a run where no gateway ran out of heap matched at every stage (258,619
  events created, sent, in Kafka and in PostgreSQL).

**Decision:** both JVM images run with `-XX:+ExitOnOutOfMemoryError`, and the gateways and the
usage consumer have `restart: unless-stopped` in Compose.
- A JVM that runs out of heap is in an unknown state: any thread may have died halfway through
  something. Exiting and starting clean is the only state that is known to be correct.
- While a gateway is down, nginx gets connection errors from it and skips it for 10 seconds
  (its defaults, `max_fails=1 fail_timeout=10s`), so the other two take the traffic.
- `unless-stopped` rather than `always`: a container stopped or killed on purpose (the
  reconciliation test kills the consumer with `docker compose kill`) stays stopped.

**Rejected:**
- *A larger heap.* It moves the point where it breaks; with no limit on requests in flight,
  enough load still runs it out.
- *A heap dump on the error* (`-XX:+HeapDumpOnOutOfMemoryError`). Useful when the cause is unknown;
  here it is known, and a few hundred MB per crash would fill the disk of a restarting container.
- *A health check that restarts the container.* Compose doesn't restart unhealthy containers on its
  own, and a check would only notice after several failed probes.

**What this does not fix:** the crash still loses the events that gateway had not yet sent, and
nothing counts them (the README's limitations say so). The cause, unlimited requests in flight,
is note 45.

## 45. Load shedding: a cap on requests in flight, and a fast 503 past it

**Problem:** each request runs on its own virtual thread, and nothing limited how many were in
flight. Past what the stack can serve, requests arrive faster than they finish, so the number in
flight grows without limit until the heap runs out (note 44). Exiting and restarting turns that
into a clean failure, but every request in flight on that gateway still fails, and its unsent
usage events are lost.

**Decision:** a servlet filter, `OverloadFilter`, admits at most `gateway.overload.max-in-flight`
`/v1/` requests at once per instance. A request that finds no free slot gets an immediate `503`
with `Retry-After: 1` and OpenAI's error format (`type: server_error`, `code: gateway_overloaded`).
- **A semaphore with `tryAcquire()`, never waiting.** Waiting for a slot would just move the pile-up
  into a queue. Answering at once tells the client to back off while the requests already admitted
  keep their memory and finish at normal speed.
- **Where it sits:** right after `RequestIdFilter` (a shed request still gets an ID and an access-log
  line) and before authentication, so turning a request away costs almost nothing.
- **A slot lasts the whole request**, released in `finally`. Streams are written on the request's
  own thread, so a stream keeps its slot until it ends, which is right: an open stream holds
  memory too.
- **Shed requests are not billed.** They never reach the pipeline, so they create no usage event.
  The load test counts them apart from answered requests, so the accounting check stays exact.
- **Per instance, not shared.** Memory is per instance, so the limit is too; three gateways admit
  three times as much. No Redis call on the way in.
- **Metrics:** `gateway_requests_in_flight` (now), `gateway_requests_shed_total`, and
  `gateway_requests_in_flight_at_arrival` (a summary whose `_max` is the peak over the last couple
  of minutes, which a gauge scraped every few seconds would miss). The dashboard shows the first
  two.

**Rejected:**
- *Tomcat's `server.tomcat.max-connections` or `accept-count`.* Connections past the limit wait in
  the operating system's queue: the client sees slowness, not a clear answer, and there is no
  metric. With nginx in front, the "connections" are nginx's pooled keep-alive connections, not
  requests, so the number would mean little.
- *Concurrency limits in nginx* (`limit_conn`). nginx doesn't know each gateway's heap, and a
  restarted gateway would still get its full share at once.
- *An adaptive limit* (like Netflix's concurrency-limits, which lowers the cap as latency rises).
  Better for real providers whose speed varies, but harder to explain and test; a fixed cap,
  measured, is enough to stop the heap from running out.
- *Shedding by priority or per client.* Every client here has its own rate limit already; overload
  is about this instance's memory, not about who is asking.

**The value:** 1,000 per instance is a placeholder until the full-stack load test measures how many
requests are in flight at the highest sustained rate and where the heap runs out. The measured value
and how it was chosen will be recorded here.
