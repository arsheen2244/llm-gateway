package dev.llmgateway.mock.stats;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /stats}: counters since the mock started. Read-only on purpose: a load test
 * reads it before and after a run and takes the difference, so nothing ever needs resetting
 * (and there's no shared state a test could change for another; design note 4).
 */
@RestController
public class StatsController {

    private final RequestStats stats;

    public StatsController(RequestStats stats) {
        this.stats = stats;
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return stats.snapshot();
    }
}
