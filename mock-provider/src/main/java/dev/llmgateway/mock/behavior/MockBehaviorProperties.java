package dev.llmgateway.mock.behavior;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knobs that control how the mock behaves when a request does not force a scenario.
 * Each one can be set with an environment variable, for example
 * {@code MOCK_LATENCY_MS=50} or {@code MOCK_ERROR_RATE=0.1}.
 *
 * @param latencyMs            delay before the first byte of every response
 * @param errorRate            probability (0..1) of answering with HTTP 500
 * @param midStreamFailureRate probability (0..1) that a stream drops halfway
 * @param chunkCount           number of content chunks in a response
 * @param chunkDelayMs         delay between streamed chunks
 */
@ConfigurationProperties(prefix = "mock")
public record MockBehaviorProperties(
        @DefaultValue("0") long latencyMs,
        @DefaultValue("0.0") double errorRate,
        @DefaultValue("0.0") double midStreamFailureRate,
        @DefaultValue("5") int chunkCount,
        @DefaultValue("10") long chunkDelayMs) {
}
