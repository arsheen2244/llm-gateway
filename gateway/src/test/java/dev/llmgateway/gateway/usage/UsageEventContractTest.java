package dev.llmgateway.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * The JSON the gateway sends must be exactly {@code contracts/usage-event.json}. The consumer
 * has the matching test for reading it, so the two modules can't drift apart (design note 39).
 * Uses the application's own ObjectMapper, as the Kafka sink does.
 */
@JsonTest
class UsageEventContractTest {

    @Autowired
    ObjectMapper json;

    @Test
    void eventIsWrittenExactlyAsTheContractSays() throws Exception {
        UsageEvent event = new UsageEvent("7f8c1c8e-3d0a-4c3e-9a51-2b1d6f0c4e11", "local-dev", "gpt-x",
                "mock-openai", "mock-gpt", 12, 34, 250, "miss", UsageStatus.SUCCESS, true,
                Instant.parse("2026-11-20T10:15:30.123Z"));

        assertThat(json.readTree(json.writeValueAsString(event)))
                .isEqualTo(json.readTree(Files.readString(Path.of("../contracts/usage-event.json"))));
    }
}
