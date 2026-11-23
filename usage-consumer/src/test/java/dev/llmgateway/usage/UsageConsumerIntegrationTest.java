package dev.llmgateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** The whole service with a real Kafka and a real PostgreSQL. Needs Docker; skipped without it. */
@SpringBootTest
@ActiveProfiles("local")
@DirtiesContext // stop this consumer before its containers go away
@Testcontainers(disabledWithoutDocker = true)
class UsageConsumerIntegrationTest {

    private static final String TOPIC = "usage-events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    private final String client = TestEvents.newClient();

    @Test
    void eventsSentTwiceInAnyOrderAreCountedOnce() throws Exception {
        List<UsageEvent> events = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            events.add(TestEvents.success(client, 10, 5));
        }
        List<UsageEvent> sent = new ArrayList<>(events);
        sent.addAll(events);
        Collections.shuffle(sent);
        for (UsageEvent event : sent) {
            send(event);
        }
        String last = sendMarker();

        waitFor(() -> requests(last) == 1); // everything before the marker has been handled
        assertThat(requests(client)).isEqualTo(50);
        assertThat(jdbc.sql("SELECT prompt_tokens FROM usage_daily WHERE client_id = ?").param(client)
                .query(Long.class).single()).isEqualTo(500);
    }

    @Test
    void aBadMessageGoesToTheDeadLetterTopicAndTheNextOnesAreStillRecorded() throws Exception {
        String badJson = "not json " + UUID.randomUUID();
        UsageEvent unstorable = new UsageEvent(UUID.randomUUID(), client, "mock-\u0000", "mock-openai", "mock-gpt",
                1, 1, 1L, "miss", UsageEvent.Status.SUCCESS, false, TestEvents.NOON);

        kafka.send(TOPIC, client, badJson).get();
        send(unstorable);
        send(TestEvents.success(client, 10, 5));

        waitFor(() -> requests(client) == 1);
        assertThat(deadLetters(2)).containsExactlyInAnyOrder(badJson, json.writeValueAsString(unstorable));
    }

    @Test
    void whilePostgresHangsEventsWaitAndNoneIsLostOrDeadLettered() throws Exception {
        send(TestEvents.success(client, 10, 5));
        waitFor(() -> requests(client) == 1);

        pausePostgres(true);
        try {
            for (int i = 0; i < 20; i++) {
                send(TestEvents.success(client, 10, 5));
            }
            Thread.sleep(15_000); // longer than the 10 s socket timeout: the write fails and is retried
        } finally {
            pausePostgres(false);
        }

        waitFor(() -> requests(client) == 21);
        assertThat(jdbc.sql("SELECT count(*) FROM usage_events WHERE client_id = ?").param(client)
                .query(Long.class).single()).isEqualTo(21);
    }

    // ---- helpers ----

    private void send(UsageEvent event) throws Exception {
        kafka.send(TOPIC, event.clientId(), json.writeValueAsString(event)).get();
    }

    /** An event with the same key (so the same partition), recorded under its own client id. */
    private String sendMarker() throws Exception {
        UsageEvent marker = TestEvents.success(TestEvents.newClient(), 0, 0);
        kafka.send(TOPIC, client, json.writeValueAsString(marker)).get();
        return marker.clientId();
    }

    private long requests(String clientId) {
        return jdbc.sql("SELECT requests FROM usage_daily WHERE client_id = ?").param(clientId)
                .query(Long.class).optional().orElse(0L);
    }

    private static List<String> deadLetters(int count) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("usage-events-dlt"));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (values.size() < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> values.add(record.value()));
            }
            return values;
        }
    }

    private static void pausePostgres(boolean paused) {
        var docker = POSTGRES.getDockerClient();
        if (paused) {
            docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 60 s");
            }
            Thread.sleep(100);
        }
    }
}
