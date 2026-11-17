package dev.llmgateway.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.provider.call.CallControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Hedging decisions (design note 30) with fake calls whose timing the test controls.
 * Hedge delay here: 150 ms by default (floor 50 ms, percentile after 3 samples).
 */
class HedgerTest {

    private final CircuitBreakerTest.FakeClock clock = new CircuitBreakerTest.FakeClock();
    private Hedger hedger = hedger(1.0);
    private ProviderGuards.Guard guard = guard(1.0);

    /** Every CallControl handed to a call, in start order (0 = primary, 1 = hedge). */
    private final List<CallControl> controls = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        hedger.close();
    }

    @Test
    void fastPrimaryIsNotHedged() {
        AtomicInteger started = new AtomicInteger();

        String result = hedger.call("p|m", guard, control -> {
            started.incrementAndGet();
            return "primary";
        });

        assertThat(result).isEqualTo("primary");
        assertThat(started).hasValue(1);
    }

    @Test
    void slowPrimaryIsHedgedTheHedgeWinsAndThePrimaryIsCancelled() {
        String result = hedger.call("p|m", guard, calls(slowUntilCancelled("primary"), fast("hedge")));

        assertThat(result).isEqualTo("hedge");
        assertThat(controls).hasSize(2);
        awaitStop(controls.get(0));
        assertThat(controls.get(0).stopReason()).isEqualTo(CallControl.Stop.CANCELLED);
        assertThat(controls.get(1).stopReason()).isEqualTo(CallControl.Stop.NONE);
    }

    @Test
    void primaryThatAnswersAfterTheHedgeStartedStillWinsIfFirst() {
        String result = hedger.call("p|m", guard, calls(sleepThen(300, "primary"), sleepThen(2_000, "hedge")));

        assertThat(result).isEqualTo("primary");
        awaitStop(controls.get(1));
        assertThat(controls.get(1).stopReason()).isEqualTo(CallControl.Stop.CANCELLED);
    }

    @Test
    void whenBothFailThePrimarysFailureIsReported() {
        assertThatThrownBy(() -> hedger.call("p|m", guard, calls(
                sleepThenFail(300, "primary failed"), sleepThenFail(0, "hedge failed"))))
                .isInstanceOf(ProviderException.class)
                .hasMessage("primary failed");
    }

    @Test
    void noHedgeWhenTheBudgetIsSpent() {
        hedger = hedger(0.0);
        guard = guard(0.0); // hedges allowed for 0% of requests

        String result = hedger.call("p|m", guard, calls(sleepThen(300, "primary"), fast("hedge")));

        assertThat(result).isEqualTo("primary");
        assertThat(controls).hasSize(1);
    }

    @Test
    void noHedgeWhileTheBreakerIsNotClosed() {
        for (int i = 0; i < 20; i++) {
            guard.breaker().tryAcquirePermission();
            guard.breaker().onFailure();
        }
        assertThat(guard.breaker().state()).isEqualTo(CircuitBreaker.State.OPEN);

        String result = hedger.call("p|m", guard, calls(sleepThen(300, "primary"), fast("hedge")));

        assertThat(result).isEqualTo("primary");
        assertThat(controls).hasSize(1);
    }

    @Test
    void delayIsTheDefaultUntilEnoughSamplesThenTheObservedPercentileAboveTheFloor() {
        assertThat(hedger.hedgeDelay("p|m")).isEqualTo(Duration.ofMillis(150));

        for (int i = 0; i < 3; i++) {
            hedger.call("p|m", guard, control -> "fast"); // ~0 ms each
        }

        assertThat(hedger.hedgeDelay("p|m")).isEqualTo(Duration.ofMillis(50)); // floor, not ~0
        assertThat(hedger.hedgeDelay("other|m")).isEqualTo(Duration.ofMillis(150)); // per provider + model
    }

    @Test
    void theCallSeesTheCallersLoggingContext() {
        MDC.put("requestId", "req-42");
        try {
            String seen = hedger.call("p|m", guard, control -> MDC.get("requestId"));

            assertThat(seen).isEqualTo("req-42");
        } finally {
            MDC.clear();
        }
    }

    // ---- helpers ----

    private Hedger hedger(double maxRatio) {
        return new Hedger(properties(maxRatio));
    }

    private ProviderGuards.Guard guard(double maxRatio) {
        return new ProviderGuards(properties(maxRatio), clock).forProvider("p");
    }

    private static ResilienceProperties properties(double maxRatio) {
        ResilienceProperties defaults = ResilienceProperties.defaults();
        return new ResilienceProperties(defaults.maxAttemptsPerProvider(), defaults.backoffBase(),
                defaults.backoffCap(), defaults.retryBudgetRatio(), defaults.retryBudgetMinPerSecond(),
                defaults.retryBudgetWindow(), defaults.breaker(),
                new ResilienceProperties.Hedging(true, 0.95, Duration.ofMillis(50), Duration.ofMillis(150), 3, 200,
                        maxRatio));
    }

    /** The first start runs {@code primary}, the second {@code hedge}; each sees its own control. */
    @SafeVarargs
    private Function<CallControl, String> calls(Function<CallControl, String>... byStartOrder) {
        AtomicInteger next = new AtomicInteger();
        return control -> {
            controls.add(control);
            return byStartOrder[next.getAndIncrement()].apply(control);
        };
    }

    private static Function<CallControl, String> fast(String value) {
        return control -> value;
    }

    private static Function<CallControl, String> sleepThen(long ms, String value) {
        return control -> {
            sleep(ms, control);
            if (control.stopReason() != CallControl.Stop.NONE) {
                throw ProviderException.of("p", Reason.IO_ERROR, "cancelled", null);
            }
            return value;
        };
    }

    private static Function<CallControl, String> sleepThenFail(long ms, String message) {
        return control -> {
            sleep(ms, control);
            throw ProviderException.errorResponse("p", 503, message);
        };
    }

    /** Like a real provider call: waits until it is cancelled (or 5 s). */
    private static Function<CallControl, String> slowUntilCancelled(String value) {
        return sleepThen(5_000, value);
    }

    /** Sleeps up to {@code ms}, waking early if the call is stopped (as an aborted socket would). */
    private static void sleep(long ms, CallControl control) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && control.stopReason() == CallControl.Stop.NONE) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void awaitStop(CallControl control) {
        long end = System.currentTimeMillis() + 2_000;
        while (control.stopReason() == CallControl.Stop.NONE && System.currentTimeMillis() < end) {
            Thread.onSpinWait();
        }
    }
}
