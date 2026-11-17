package dev.llmgateway.mock.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import dev.llmgateway.mock.behavior.Scenario;
import org.springframework.stereotype.Component;

/**
 * Counts what the mock received, so load tests can measure the request volume a gateway
 * really sends upstream (retries and hedges included) at the provider's side.
 * Lock-free counters: each is an independent atomic increment.
 */
@Component
public class RequestStats {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong midStreamFailures = new AtomicLong();
    private final AtomicLong slow = new AtomicLong();

    public void record(Scenario scenario) {
        requests.incrementAndGet();
        switch (scenario.kind()) {
            case HTTP_ERROR -> errors.incrementAndGet();
            case FAIL_MID_STREAM -> midStreamFailures.incrementAndGet();
            case SUCCESS -> {
                if (scenario.extraLatencyMs() > 0) {
                    slow.incrementAndGet();
                }
            }
        }
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("requests", requests.get());
        counts.put("errors", errors.get());
        counts.put("midStreamFailures", midStreamFailures.get());
        counts.put("slow", slow.get());
        return counts;
    }
}
