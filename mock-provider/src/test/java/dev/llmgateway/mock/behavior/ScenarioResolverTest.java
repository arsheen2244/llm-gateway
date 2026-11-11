package dev.llmgateway.mock.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Iterator;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;

class ScenarioResolverTest {

    private static final MockBehaviorProperties RATES =
            new MockBehaviorProperties(0, 0.2, 0.3, 6, 0);

    @Test
    void modelNameCanForceHttpError() {
        Scenario scenario = resolver(1.0).resolve("mock-error-503");

        assertThat(scenario).isEqualTo(Scenario.httpError(503));
    }

    @Test
    void modelNameCanForceMidStreamFailure() {
        Scenario scenario = resolver(1.0).resolve("mock-claude-fail-after-2");

        assertThat(scenario).isEqualTo(Scenario.failMidStream(2));
    }

    @Test
    void lowRandomValueTriggersConfiguredErrorRate() {
        Scenario scenario = resolver(0.1).resolve("mock-gpt");

        assertThat(scenario).isEqualTo(Scenario.httpError(500));
    }

    @Test
    void secondRandomDrawDecidesMidStreamFailureHalfwayThrough() {
        // first draw 0.5 >= errorRate 0.2 (no error), second 0.25 < 0.3 (fail)
        Scenario scenario = resolver(0.5, 0.25).resolve("mock-gpt");

        assertThat(scenario).isEqualTo(Scenario.failMidStream(3));
    }

    @Test
    void highRandomValuesMeanSuccess() {
        Scenario scenario = resolver(0.9, 0.9).resolve("mock-gpt");

        assertThat(scenario).isEqualTo(Scenario.success());
    }

    @Test
    void nullModelIsTreatedAsUnforced() {
        assertThat(resolver(0.9, 0.9).resolve(null)).isEqualTo(Scenario.success());
    }

    @Test
    void shouldDropBeforeOnlyAppliesFromTheFailurePointOn() {
        Scenario scenario = Scenario.failMidStream(2);

        assertThat(scenario.shouldDropBefore(1)).isFalse();
        assertThat(scenario.shouldDropBefore(2)).isTrue();
        assertThat(Scenario.success().shouldDropBefore(99)).isFalse();
    }

    /** A resolver whose "random" numbers are the given values, in order. */
    private static ScenarioResolver resolver(Double... values) {
        Iterator<Double> sequence = Arrays.asList(values).iterator();
        DoubleSupplier random = sequence::next;
        return new ScenarioResolver(RATES, random);
    }
}
