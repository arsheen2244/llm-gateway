package dev.llmgateway.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.config.GatewayProperties;
import dev.llmgateway.gateway.config.GatewayProperties.Provider;
import dev.llmgateway.gateway.config.GatewayProperties.Route;
import dev.llmgateway.gateway.config.GatewayProperties.Target;
import dev.llmgateway.gateway.config.GatewayProperties.Timeouts;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ResponseHeaders;
import dev.llmgateway.gateway.provider.AdapterRegistry;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderAdapter;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.provider.call.ProviderCaller;
import dev.llmgateway.gateway.routing.ModelRouter;
import dev.llmgateway.gateway.routing.ProviderConfig;
import dev.llmgateway.gateway.routing.ProviderTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The executor's decisions (design note 27) with scripted fake providers: "a" first, then
 * "b". Breakers here open after 4 calls at 50% failures; backoff waits are recorded, not slept.
 */
class ResilientExecutorTest {

    /** Hedging has its own tests (HedgerTest); here every attempt is a single call. */
    private static final ResilienceProperties.Hedging NO_HEDGING = new ResilienceProperties.Hedging(
            false, 0.95, Duration.ofMillis(300), Duration.ofSeconds(2), 20, 200, 0.05);

    private static final ChatCompletionRequest REQUEST = new ChatCompletionRequest("m",
            List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);

    private final CircuitBreakerTest.FakeClock clock = new CircuitBreakerTest.FakeClock();
    private final ScriptedAdapter adapter = new ScriptedAdapter();
    private final List<Duration> sleeps = new ArrayList<>();
    private final GatewayProperties gatewayProperties = new GatewayProperties(
            Map.of("a", new Provider("fake", "http://a", "k"),
                    "b", new Provider("fake", "http://b", "k"),
                    "nokey", new Provider("fake", "http://nokey", "")),
            List.of(new Route("*", null, List.of(new Target("a", null), new Target("b", "model-for-b")))),
            Timeouts.of(Duration.ofSeconds(5), Duration.ofSeconds(5)));
    private final ModelRouter router = new ModelRouter(gatewayProperties);
    private final ProviderCaller caller = new ProviderCaller(gatewayProperties);
    private final ResilienceProperties resilience = new ResilienceProperties(2, Duration.ofMillis(100),
            Duration.ofSeconds(2), 0.1, 1, Duration.ofSeconds(10),
            new ResilienceProperties.Breaker(0.5, 4, Duration.ofSeconds(30), Duration.ofSeconds(30), 3),
            NO_HEDGING);
    private ProviderGuards guards = new ProviderGuards(resilience, clock);
    private ResilientExecutor executor = executor(resilience);

    @AfterEach
    void tearDown() {
        caller.close();
    }

    @Test
    void healthyPrimaryAnswersWithOneCall() {
        adapter.script("a", ok());

        assertThat(executor.complete(context()).id()).isEqualTo("from-a");
        assertThat(adapter.calls).containsExactly("a");
    }

    @Test
    void retryableFailureIsRetriedOnTheSameProviderAfterABackoff() {
        adapter.script("a", fail(503), ok());

        assertThat(executor.complete(context()).id()).isEqualTo("from-a");
        assertThat(adapter.calls).containsExactly("a", "a");
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void providerThatKeepsFailingFailsOverToTheNextOneWithItsModelName() {
        adapter.script("a", fail(503), fail(503));
        adapter.script("b", ok());

        ChatContext context = context();
        ChatCompletionResponse response = executor.complete(context);

        assertThat(response.id()).isEqualTo("from-b");
        assertThat(adapter.calls).containsExactly("a", "a", "b");
        assertThat(adapter.modelsSent).containsExactly("m", "m", "model-for-b");
        assertThat(context.trace().provider()).as("the provider that answered, for usage events").isEqualTo("b");
    }

    @Test
    void clientErrorIsReturnedAtOnceWithoutRetryOrFailover() {
        adapter.script("a", fail(400));

        assertThatThrownBy(() -> executor.complete(context()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.upstreamStatus()).isEqualTo(400));
        assertThat(adapter.calls).containsExactly("a");
    }

    @Test
    void rejectedCredentialsSkipRetryButFailOver() {
        adapter.script("a", fail(401));
        adapter.script("b", ok());

        assertThat(executor.complete(context()).id()).isEqualTo("from-b");
        assertThat(adapter.calls).containsExactly("a", "b");
    }

    @Test
    void openBreakerSkipsTheProviderWithoutCallingIt() {
        for (int i = 0; i < 4; i++) {
            guards.forProvider("a").breaker().tryAcquirePermission();
            guards.forProvider("a").breaker().onFailure();
        }
        adapter.script("b", ok());

        assertThat(executor.complete(context()).id()).isEqualTo("from-b");
        assertThat(adapter.calls).containsExactly("b");
    }

    @Test
    void allProvidersFailingReturnsTheLastRealFailure() {
        adapter.script("a", fail(503), fail(503));
        adapter.script("b", fail(500), fail(502));

        assertThatThrownBy(() -> executor.complete(context()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.provider()).isEqualTo("b");
                    assertThat(e.upstreamStatus()).isEqualTo(502);
                });
        assertThat(adapter.calls).hasSize(4); // 2 attempts each, never more
    }

    @Test
    void everyBreakerOpenIsCircuitOpen() {
        for (String name : List.of("a", "b")) {
            for (int i = 0; i < 4; i++) {
                guards.forProvider(name).breaker().tryAcquirePermission();
                guards.forProvider(name).breaker().onFailure();
            }
        }

        assertThatThrownBy(() -> executor.complete(context()))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CIRCUIT_OPEN));
        assertThat(adapter.calls).isEmpty();
    }

    @Test
    void exhaustedRetryBudgetFailsOverWithoutRetrying() {
        ResilienceProperties noBudget = new ResilienceProperties(2, Duration.ofMillis(100), Duration.ofSeconds(2),
                0.0, 0, Duration.ofSeconds(10), resilience.breaker(), NO_HEDGING);
        executor = executor(noBudget);
        adapter.script("a", fail(503));
        adapter.script("b", ok());

        assertThat(executor.complete(context()).id()).isEqualTo("from-b");
        assertThat(adapter.calls).containsExactly("a", "b");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void providerWithoutKeyIsSkipped() {
        GatewayProperties withNoKeyFirst = new GatewayProperties(gatewayProperties.providers(),
                List.of(new Route("*", null, List.of(new Target("nokey", null), new Target("b", null)))),
                gatewayProperties.timeouts());
        adapter.script("b", ok());

        ChatCompletionResponse response = executor.complete(context(new ModelRouter(withNoKeyFirst).targets("m")));

        assertThat(response.id()).isEqualTo("from-b");
        assertThat(adapter.calls).containsExactly("b");
    }

    @Test
    void streamFailsOverWhileNothingHasReachedTheClient() {
        adapter.script("a", fail(503), fail(503));
        adapter.script("b", ok());
        List<ChatCompletionChunk> received = new ArrayList<>();

        executor.stream(context(), received::add);

        assertThat(adapter.calls).containsExactly("a", "a", "b");
        assertThat(received).extracting(ChatCompletionChunk::id).containsExactly("from-b");
    }

    @Test
    void streamThatAlreadyReachedTheClientIsNeverFailedOver() {
        adapter.script("a", chunkThenBreak());
        adapter.script("b", ok());
        List<ChatCompletionChunk> received = new ArrayList<>();

        assertThatThrownBy(() -> executor.stream(context(), received::add))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.STREAM_INTERRUPTED));
        assertThat(adapter.calls).containsExactly("a");
        assertThat(received).hasSize(1);
    }

    @Test
    void failuresFeedTheBreakerAndClientErrorsDoNot() {
        for (int i = 0; i < 4; i++) {
            adapter.script("a", fail(400));
            assertThatThrownBy(() -> executor.complete(context())).isInstanceOf(ProviderException.class);
        }
        assertThat(guards.forProvider("a").breaker().state()).isEqualTo(CircuitBreaker.State.CLOSED);

        adapter.script("a", fail(503), fail(503));
        adapter.script("b", ok());
        executor.complete(context());
        adapter.script("a", fail(503), fail(503));
        adapter.script("b", ok());
        executor.complete(context());

        assertThat(guards.forProvider("a").breaker().state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void clientDisconnectPassesThroughAndIsNotCountedAsAFailure() {
        adapter.script("a", ok());
        RuntimeException gone = new RuntimeException("client gone");

        assertThatThrownBy(() -> executor.stream(context(), chunk -> { throw gone; })).isSameAs(gone);
        assertThat(adapter.calls).containsExactly("a");
    }

    // ---- helpers ----

    private ResilientExecutor executor(ResilienceProperties properties) {
        guards = new ProviderGuards(properties, clock);
        AdapterRegistry registry = new AdapterRegistry(List.of(adapter), router);
        return new ResilientExecutor(registry, caller, guards, properties,
                new Backoff(properties.backoffBase(), properties.backoffCap(), () -> 0.5), sleeps::add,
                new Hedger(properties));
    }

    private ChatContext context() {
        return context(router.targets("m"));
    }

    private static ChatContext context(List<ProviderTarget> targets) {
        return new ChatContext(null, REQUEST, targets.get(0).provider(), new ResponseHeaders(), targets,
                dev.llmgateway.gateway.pipeline.RequestOptions.DEFAULTS);
    }

    private interface Step {
        ChatCompletionResponse run(String provider, ChunkSink sink);
    }

    private static Step ok() {
        return (provider, sink) -> {
            if (sink != null) {
                sink.accept(new ChatCompletionChunk("from-" + provider, "chat.completion.chunk", 0, "m", List.of(), null));
                return null;
            }
            return new ChatCompletionResponse("from-" + provider, "chat.completion", 0, "m", List.of(), null);
        };
    }

    private static Step fail(int status) {
        return (provider, sink) -> {
            throw ProviderException.errorResponse(provider, status, "HTTP " + status);
        };
    }

    private static Step chunkThenBreak() {
        return (provider, sink) -> {
            sink.accept(new ChatCompletionChunk("from-" + provider, "chat.completion.chunk", 0, "m", List.of(), null));
            throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED, "cut", null);
        };
    }

    /** Plays each provider's scripted steps in order and records every call. */
    private static final class ScriptedAdapter implements ProviderAdapter {
        private final Map<String, Deque<Step>> scripts = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<String> modelsSent = new ArrayList<>();

        void script(String provider, Step... steps) {
            scripts.computeIfAbsent(provider, p -> new ArrayDeque<>()).addAll(List.of(steps));
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public ChatCompletionResponse complete(ProviderConfig provider, ChatCompletionRequest request) {
            return next(provider, request).run(provider.name(), null);
        }

        @Override
        public void stream(ProviderConfig provider, ChatCompletionRequest request, ChunkSink sink) {
            next(provider, request).run(provider.name(), sink);
        }

        private Step next(ProviderConfig provider, ChatCompletionRequest request) {
            calls.add(provider.name());
            modelsSent.add(request.model());
            Deque<Step> steps = scripts.get(provider.name());
            if (steps == null || steps.isEmpty()) {
                throw new AssertionError("unexpected call to " + provider.name());
            }
            return steps.removeFirst();
        }
    }
}
