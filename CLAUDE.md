# LLM Gateway

Portfolio project for SDE job applications. An OpenAI-compatible proxy in front of LLM
providers (OpenAI, Anthropic, and a local mock), adding routing, streaming relay, rate
limiting, resilience, caching and cost tracking over a five-week roadmap (see README).

## Working with the owner

- The owner is a final-year CS student who must **explain and defend every design
  decision in interviews**. Explain reasoning as you go; prefer clear, conventional code
  over clever code.
- **Plan first and wait for approval** before writing code for a new piece of work.
- **Real design choices: stop and ask**, with options and trade-offs. Don't decide silently.
- **Small steps:** after each step, run the tests, summarise what was built and why, and
  make one commit with a clear message.
- **Record every significant decision in `docs/design-notes.md`:** what was chosen, what
  was rejected, and why. Add new numbered notes; mark superseded ones rather than deleting them.
- **Never report a benchmark number that wasn't actually measured.** Invalid runs (errors,
  crashes) are discarded, not reported.
- Week 2 rate-limit Lua scripts: the owner first planned to write them, then asked
  Claude to. They must still be explained clearly enough for the owner to defend them.

## Stack

Java 21, Spring Boot 3.5 (Spring MVC on **virtual threads**, not WebFlux), Maven
(multi-module, use `./mvnw`), JUnit 5, Apache HttpClient 5 for provider calls, Docker
Compose, k6, GitHub Actions.

## Layout

```
gateway/         the gateway app (api, pipeline, routing, provider adapters, streaming, observability)
mock-provider/   fake provider speaking OpenAI and Anthropic formats; all tests use it
load-test/       k6 scripts
scripts/         smoke test
docs/            design-notes.md
```

## Constraints

- **Tests and load tests never call real providers.** Real provider keys are forced
  empty in tests.
- **Secrets only from environment variables.** Never commit keys. Gateway client keys
  are stored in config as SHA-256 hashes, not plaintext.
- **No Docker on the owner's laptop** (no WSL, and memory is tight). Docker-dependent
  work (Testcontainers, multi-instance Compose, k6 against Compose) runs in GitHub
  Actions. Testcontainers tests must use `disabledWithoutDocker = true` so local builds
  pass.
- When running the jars locally, cap JVM memory (see README benchmark setup) or they
  crash for lack of native memory.

## Commands

```bash
./mvnw verify                      # build + all tests (Docker tests skip locally)
./mvnw -pl gateway -am package -DskipTests
k6 run load-test/gateway-overhead.js
bash scripts/smoke-test.sh http://localhost:8080
```
