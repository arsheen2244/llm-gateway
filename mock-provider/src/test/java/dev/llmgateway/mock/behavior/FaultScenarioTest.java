package dev.llmgateway.mock.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** The week-3 fault knobs: the slow tail and flapping. */
class FaultScenarioTest {

    @Test
    void modelNameCanForceASlowAnswer() {
        Scenario scenario = resolver(MockBehaviorProperties.basic(0, 0, 0, 5, 0), 0, 0.9).resolve("mock-slow-1500");

        assertThat(scenario).isEqualTo(Scenario.slow(1500));
        assertThat(scenario.kind()).isEqualTo(Scenario.Kind.SUCCESS);
    }

    @Test
    void slowRateAddsTheConfiguredLatencyToSomeRequests() {
        MockBehaviorProperties slowTail = new MockBehaviorProperties(0, 0, 0, 5, 0, 0.05, 2000, 0, 0);

        // draws: error (0.9, no), mid-stream (0.9, no), slow (0.01 < 0.05, yes)
        assertThat(resolver(slowTail, 0, 0.9, 0.9, 0.01).resolve("mock-gpt")).isEqualTo(Scenario.slow(2000));
        // same, but the slow draw misses
        assertThat(resolver(slowTail, 0, 0.9, 0.9, 0.5).resolve("mock-gpt")).isEqualTo(Scenario.success());
    }

    @Test
    void flappingIsDownAtTheStartOfEveryPeriodAndUpOtherwise() {
        // down for the first 3 s of every 10 s
        MockBehaviorProperties flapping = new MockBehaviorProperties(0, 0, 0, 5, 0, 0, 2000, 10_000, 3_000);

        assertThat(resolver(flapping, 20_000, 0.9, 0.9).resolve("mock-gpt")).isEqualTo(Scenario.httpError(503));
        assertThat(resolver(flapping, 22_999, 0.9, 0.9).resolve("mock-gpt")).isEqualTo(Scenario.httpError(503));
        assertThat(resolver(flapping, 23_000, 0.9, 0.9).resolve("mock-gpt")).isEqualTo(Scenario.success());
        assertThat(resolver(flapping, 29_999, 0.9, 0.9).resolve("mock-gpt")).isEqualTo(Scenario.success());
    }

    @Test
    void forcedScenariosWinOverFlapping() {
        MockBehaviorProperties alwaysDown = new MockBehaviorProperties(0, 0, 0, 5, 0, 0, 2000, 10_000, 10_000);

        assertThat(resolver(alwaysDown, 5_000).resolve("mock-slow-100")).isEqualTo(Scenario.slow(100));
    }

    /** A resolver at a fixed time, with the given "random" numbers in order. */
    private static ScenarioResolver resolver(MockBehaviorProperties properties, long nowMillis, double... draws) {
        int[] next = {0};
        Clock clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        return new ScenarioResolver(properties, () -> draws[next[0]++], clock);
    }
}
