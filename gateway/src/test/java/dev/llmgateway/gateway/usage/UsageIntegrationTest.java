package dev.llmgateway.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Usage events through the real gateway and mock provider, recorded instead of sent to Kafka. */
@Import(UsageIntegrationTest.RecordingSinkConfig.class)
class UsageIntegrationTest extends GatewayIntegrationTest {

    @TestConfiguration
    static class RecordingSinkConfig {
        @Bean
        @Primary
        RecordingUsageSink recordingUsageSink() {
            return new RecordingUsageSink();
        }
    }

    @Autowired
    RecordingUsageSink sink;

    @BeforeEach
    void clear() {
        sink.events.clear();
    }

    @Test
    void aCompletedRequestProducesOneEventWithTheProvidersTokenCounts() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-gpt", false));

        UsageEvent event = sink.only();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(event.clientId()).isEqualTo("test-client");
        assertThat(event.model()).isEqualTo("mock-gpt");
        assertThat(event.provider()).isEqualTo("mock-openai");
        assertThat(event.upstreamModel()).isEqualTo("mock-gpt");
        assertThat(event.status()).isEqualTo(UsageStatus.SUCCESS);
        assertThat(event.promptTokens())
                .isEqualTo(json.readTree(response.body()).at("/usage/prompt_tokens").asInt())
                .isPositive();
        assertThat(event.completionTokens())
                .isEqualTo(json.readTree(response.body()).at("/usage/completion_tokens").asInt());
    }

    @Test
    void aStreamIsRecordedWithTokensEvenWhenTheClientDidNotAskForUsage() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-gpt", true));

        UsageEvent event = sink.only();
        assertThat(response.body()).doesNotContain("\"usage\"");
        assertThat(event.stream()).isTrue();
        assertThat(event.status()).isEqualTo(UsageStatus.SUCCESS);
        assertThat(event.completionTokens()).isPositive();
    }

    @Test
    void aCacheHitIsRecordedWithoutAProvider() throws Exception {
        String body = """
                {"model":"mock-gpt","temperature":0,"messages":[{"role":"user","content":"Usage %s"}]}"""
                .formatted(UUID.randomUUID());
        post(body);
        post(body);

        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(0).cache()).isEqualTo("miss");
        assertThat(sink.events.get(0).provider()).isEqualTo("mock-openai");
        assertThat(sink.events.get(1).cache()).isEqualTo("exact");
        assertThat(sink.events.get(1).provider()).isNull();
        assertThat(sink.events.get(1).upstreamModel()).isNull();
        assertThat(sink.events).extracting(UsageEvent::eventId).doesNotHaveDuplicates();
    }
}
