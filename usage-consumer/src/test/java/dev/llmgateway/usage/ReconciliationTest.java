package dev.llmgateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.llmgateway.usage.UsageEvent.Status;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Reconciliation (design note 41): a known set of events, a consumer that is killed partway
 * through (twice), and totals in PostgreSQL that must match exactly.
 *
 * <p>The consumer runs as its own JVM and is killed with SIGKILL, so no shutdown code runs: no
 * final commit, no closed connections. Needs Docker; skipped without it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationTest {

    private static final int EVENTS = 6_000;
    private static final int SENT_TWICE = 600;
    private static final int PARTITIONS = 3;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @TempDir
    Path logs;

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final String run = UUID.randomUUID().toString();
    private final String topic = "usage-" + run;
    private JdbcClient jdbc;
    private Process consumer;
    private int starts;

    @AfterEach
    void stopConsumer() throws Exception {
        if (consumer != null && consumer.isAlive()) {
            kill();
        }
    }

    /**
     * {@code record} is the production setting. {@code batch} commits offsets only after each
     * poll's worth of events (up to 500), so a kill is sure to leave events that are in
     * PostgreSQL but not committed in Kafka: the case that double-counts without the store's
     * "only if the event ID is new" rule. With {@code record} that gap is too short to hit on demand.
     */
    @ParameterizedTest
    @CsvSource({"record,false", "batch,true"})
    void totalsMatchExactlyAfterTheConsumerIsKilledPartway(String ackMode, boolean redeliveryCertain)
            throws Exception {
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        List<UsageEvent> events = events();
        send(events);

        start(ackMode);
        waitFor(() -> recorded() >= EVENTS / 5);
        kill();
        long afterFirstKill = settled();
        assertThat(afterFirstKill).as("killed partway: some recorded, some not").isBetween(1L, EVENTS - 1L);
        if (redeliveryCertain) {
            assertThat(committedOffsets()).as("recorded events whose offsets were not committed")
                    .isLessThan(afterFirstKill);
        }

        start(ackMode);
        waitFor(() -> recorded() >= Math.max(afterFirstKill + 1, EVENTS / 2));
        kill();
        long afterSecondKill = settled();
        assertThat(afterSecondKill).as("killed partway again").isBetween(afterFirstKill + 1, EVENTS - 1L);

        start(ackMode);
        // Done when Kafka has nothing left for the consumer, so late duplicates are included.
        waitFor(() -> committedOffsets() == EVENTS + SENT_TWICE);

        assertThat(recorded()).as("each event once").isEqualTo(EVENTS);
        assertThat(recordedIds()).isEqualTo(events.stream().map(UsageEvent::eventId).sorted().toList());
        assertThat(dailyTotals()).isEqualTo(expectedTotals(events));
    }

    // ---- the known events and what they must add up to ----

    /** Every kind of event, over 12 clients and 2 days, from a fixed seed. */
    private List<UsageEvent> events() {
        Random random = new Random(42);
        List<UsageEvent> events = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            String client = run + "-" + random.nextInt(12);
            int prompt = random.nextInt(2000);
            int completion = 1 + random.nextInt(1000);
            var at = TestEvents.NOON.plus(Duration.ofDays(random.nextInt(2)));
            events.add(switch (random.nextInt(10)) {
                case 0 -> TestEvents.event(client, null, null, 0, 0, "none", Status.RATE_LIMITED, at);
                case 1 -> TestEvents.event(client, "mock-openai", "mock-gpt", 0, 0, "miss", Status.PROVIDER_ERROR, at);
                case 2 -> TestEvents.event(client, null, null, prompt, completion, "exact", Status.SUCCESS, at);
                case 3 -> TestEvents.event(client, "mock-openai", "no-price", prompt, completion, "miss",
                        Status.SUCCESS, at);
                default -> TestEvents.event(client, "mock-openai", "mock-gpt", prompt, completion, "miss",
                        Status.SUCCESS, at);
            });
        }
        return events;
    }

    private record Key(String client, LocalDate day) {
    }

    /** Cost as whole millionths of a dollar: mock-gpt is $1 in and $2 out per million tokens. */
    private record Totals(long requests, long successes, long rateLimited, long failed, long hits, long prompt,
                          long completion, long cachedPrompt, long cachedCompletion, long costMicros, long unpriced) {
    }

    /** Added up here from scratch, sharing no code with the store. */
    private static Map<Key, Totals> expectedTotals(List<UsageEvent> events) {
        Map<Key, long[]> sums = new HashMap<>();
        for (UsageEvent e : events) {
            LocalDate day = LocalDate.parse(e.occurredAt().toString().substring(0, 10));
            long[] s = sums.computeIfAbsent(new Key(e.clientId(), day), k -> new long[11]);
            boolean hit = e.cache().equals("exact");
            s[0]++;
            s[1] += e.status() == Status.SUCCESS ? 1 : 0;
            s[2] += e.status() == Status.RATE_LIMITED ? 1 : 0;
            s[3] += e.status() == Status.PROVIDER_ERROR ? 1 : 0;
            s[4] += hit ? 1 : 0;
            s[hit ? 7 : 5] += e.promptTokens();
            s[hit ? 8 : 6] += e.completionTokens();
            if ("mock-gpt".equals(e.upstreamModel())) {
                s[9] += e.promptTokens() + 2L * e.completionTokens();
            }
            s[10] += "no-price".equals(e.upstreamModel()) ? 1 : 0;
        }
        Map<Key, Totals> totals = new HashMap<>();
        sums.forEach((key, s) -> totals.put(key,
                new Totals(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10])));
        return totals;
    }

    private Map<Key, Totals> dailyTotals() {
        Map<Key, Totals> totals = new HashMap<>();
        jdbc.sql("SELECT * FROM usage_daily WHERE client_id LIKE ?").param(run + "-%").query(row -> {
            totals.put(new Key(row.getString("client_id"), row.getObject("day", LocalDate.class)), new Totals(
                    row.getLong("requests"), row.getLong("successful_requests"),
                    row.getLong("rate_limited_requests"), row.getLong("failed_requests"), row.getLong("cache_hits"),
                    row.getLong("prompt_tokens"), row.getLong("completion_tokens"),
                    row.getLong("cached_prompt_tokens"), row.getLong("cached_completion_tokens"),
                    // Exact, or it throws: a cost with a fraction of a millionth would be wrong too.
                    row.getBigDecimal("cost_usd").multiply(BigDecimal.valueOf(1_000_000)).longValueExact(),
                    row.getLong("unpriced_requests")));
        });
        return totals;
    }

    // ---- Kafka ----

    /** Sends every event, and some of them a second time, in random order. */
    private void send(List<UsageEvent> events) throws Exception {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get();
        }
        List<UsageEvent> toSend = new ArrayList<>(events);
        toSend.addAll(events.subList(0, SENT_TWICE));
        Collections.shuffle(toSend, new Random(7));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
                new StringSerializer(), new StringSerializer())) {
            List<java.util.concurrent.Future<?>> sent = new ArrayList<>();
            for (UsageEvent event : toSend) {
                sent.add(producer.send(new ProducerRecord<>(topic, event.clientId(), json.writeValueAsString(event))));
            }
            for (var future : sent) {
                future.get();
            }
        }
    }

    /** How many messages the consumer has told Kafka it is done with. */
    private long committedOffsets() {
        try (Admin admin = admin()) {
            return admin.listConsumerGroupOffsets(run).partitionsToOffsetAndMetadata().get().values().stream()
                    .mapToLong(offset -> offset == null ? 0 : offset.offset()).sum();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    // ---- PostgreSQL ----

    private long recorded() {
        boolean schemaExists = jdbc.sql("SELECT to_regclass('usage_daily') IS NOT NULL")
                .query(Boolean.class).single();
        return !schemaExists ? 0 : jdbc.sql("SELECT count(*) FROM usage_events WHERE client_id LIKE ?")
                .param(run + "-%").query(Long.class).single();
    }

    /** The count once PostgreSQL has finished with the killed process's connections. */
    private long settled() throws InterruptedException {
        waitFor(() -> jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                         WHERE application_name = 'PostgreSQL JDBC Driver' AND pid <> pg_backend_pid()""")
                .query(Long.class).single() == 0);
        return recorded();
    }

    private List<UUID> recordedIds() {
        return jdbc.sql("SELECT event_id FROM usage_events WHERE client_id LIKE ?").param(run + "-%")
                .query(UUID.class).list().stream().sorted().toList();
    }

    // ---- the consumer process ----

    private void start(String ackMode) throws IOException {
        File log = logs.resolve("consumer-" + (++starts) + ".log").toFile();
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
                UsageConsumerApplication.class.getName(),
                "--spring.profiles.active=local",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.group-id=" + run,
                "--spring.kafka.listener.ack-mode=" + ackMode,
                // How long Kafka waits before giving a killed consumer's partitions away (default 45 s).
                "--spring.kafka.consumer.properties.session.timeout.ms=6000",
                "--usage.topic=" + topic);
        builder.environment().put("CLASSPATH", System.getProperty("java.class.path"));
        consumer = builder.redirectErrorStream(true).redirectOutput(log).start();
    }

    /** SIGKILL: the process gets no chance to finish or commit anything. */
    private void kill() throws InterruptedException {
        assertThat(consumer.isAlive()).as("the consumer was still running when killed").isTrue();
        consumer.destroyForcibly();
        assertThat(consumer.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }

    private void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Not reached within 90 s. Recorded: " + recorded() + ", committed: "
                        + committedOffsets() + ". End of the consumer's log:\n" + logTail());
            }
            Thread.sleep(10);
        }
    }

    private String logTail() {
        try {
            List<String> lines = Files.readAllLines(logs.resolve("consumer-" + starts + ".log"));
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
        } catch (IOException e) {
            return e.toString();
        }
    }
}
