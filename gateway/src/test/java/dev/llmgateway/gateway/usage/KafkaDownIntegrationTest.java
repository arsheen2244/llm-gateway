package dev.llmgateway.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The gateway with the Kafka sink and no Kafka at all: requests must succeed as usual, with
 * the events waiting in memory (design note 38). Needs no Docker: nothing listens on port 1.
 */
@TestPropertySource(properties = {
        "gateway.usage.sink=kafka",
        "gateway.usage.kafka.bootstrap-servers=127.0.0.1:1",
        // Don't spend time at shutdown trying to send to a Kafka that isn't there.
        "gateway.usage.kafka.shutdown-timeout=0s"})
class KafkaDownIntegrationTest extends GatewayIntegrationTest {

    @Autowired
    UsageSink sink;

    @Test
    void requestsSucceedAndEventsWaitInMemory() throws Exception {
        assertThat(sink).isInstanceOf(KafkaUsageSink.class);
        KafkaUsageSink kafkaSink = (KafkaUsageSink) sink;

        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            HttpResponse<String> response = post(chatBody("mock-gpt", false));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(response.statusCode()).isEqualTo(200);
            // The mock answers in milliseconds; Kafka's 10 s metadata wait must not show here.
            assertThat(elapsedMs).isLessThan(2_000);
        }

        assertThat(kafkaSink.sent()).isZero();
        assertThat(kafkaSink.dropped()).isZero();
    }
}
