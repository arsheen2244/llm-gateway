package dev.llmgateway.mock.behavior;

import java.time.Clock;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Picks the {@link Scenario} for a request.
 *
 * <p>A model name can force an outcome, so tests are deterministic and the
 * scenarios also work through the gateway (which forwards the model name):
 * <ul>
 *   <li>{@code ...error-503...}: respond with HTTP 503</li>
 *   <li>{@code ...fail-after-2...}: stream 2 content chunks, then drop the connection</li>
 *   <li>{@code ...slow-1500...}: answer normally, 1500 ms later than usual</li>
 * </ul>
 * Otherwise, in this order:
 * <ol>
 *   <li><b>Flapping</b> (if configured): during the first {@code flapDownMs} of every
 *       {@code flapPeriodMs}, answer 503. Driven by the clock, so the mock is down and up
 *       in a regular rhythm, like a provider that keeps failing and recovering.</li>
 *   <li>Random outcomes from {@link MockBehaviorProperties}: error, mid-stream failure,
 *       then the slow tail.</li>
 * </ol>
 */
@Component
public class ScenarioResolver {

    private static final Pattern FORCED_ERROR = Pattern.compile("error-(\\d{3})");
    private static final Pattern FORCED_FAIL_AFTER = Pattern.compile("fail-after-(\\d+)");
    private static final Pattern FORCED_SLOW = Pattern.compile("slow-(\\d+)");

    private final MockBehaviorProperties properties;
    private final DoubleSupplier random;
    private final Clock clock;

    public ScenarioResolver(MockBehaviorProperties properties, DoubleSupplier random, Clock clock) {
        this.properties = properties;
        this.random = random;
        this.clock = clock;
    }

    public Scenario resolve(String model) {
        String name = model == null ? "" : model;

        Matcher error = FORCED_ERROR.matcher(name);
        if (error.find()) {
            return Scenario.httpError(Integer.parseInt(error.group(1)));
        }
        Matcher failAfter = FORCED_FAIL_AFTER.matcher(name);
        if (failAfter.find()) {
            return Scenario.failMidStream(Integer.parseInt(failAfter.group(1)));
        }
        Matcher slow = FORCED_SLOW.matcher(name);
        if (slow.find()) {
            return Scenario.slow(Long.parseLong(slow.group(1)));
        }
        if (flappedDown()) {
            return Scenario.httpError(503);
        }
        return randomScenario();
    }

    private boolean flappedDown() {
        long period = properties.flapPeriodMs();
        return period > 0 && clock.millis() % period < properties.flapDownMs();
    }

    private Scenario randomScenario() {
        if (random.getAsDouble() < properties.errorRate()) {
            return Scenario.httpError(500);
        }
        if (random.getAsDouble() < properties.midStreamFailureRate()) {
            return Scenario.failMidStream(properties.chunkCount() / 2);
        }
        // Only draw when a slow tail is configured, so the sequence of random draws (which
        // unit tests rely on) is unchanged otherwise.
        if (properties.slowRate() > 0 && random.getAsDouble() < properties.slowRate()) {
            return Scenario.slow(properties.slowLatencyMs());
        }
        return Scenario.success();
    }
}
