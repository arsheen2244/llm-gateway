package dev.llmgateway.mock.behavior;

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
 * </ul>
 * Any other model name gets a random outcome based on {@link MockBehaviorProperties}.
 */
@Component
public class ScenarioResolver {

    private static final Pattern FORCED_ERROR = Pattern.compile("error-(\\d{3})");
    private static final Pattern FORCED_FAIL_AFTER = Pattern.compile("fail-after-(\\d+)");

    private final MockBehaviorProperties properties;
    private final DoubleSupplier random;

    public ScenarioResolver(MockBehaviorProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
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
        return randomScenario();
    }

    private Scenario randomScenario() {
        if (random.getAsDouble() < properties.errorRate()) {
            return Scenario.httpError(500);
        }
        if (random.getAsDouble() < properties.midStreamFailureRate()) {
            return Scenario.failMidStream(properties.chunkCount() / 2);
        }
        return Scenario.success();
    }
}
