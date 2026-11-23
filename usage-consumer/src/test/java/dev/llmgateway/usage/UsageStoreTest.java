package dev.llmgateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.llmgateway.usage.UsageEvent.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The store against a real PostgreSQL. The test itself runs without a transaction, so the
 * store's own transactions really commit or roll back. Needs Docker; skipped without it.
 */
@JdbcTest
@Import(UsageStore.class)
@ActiveProfiles("local") // the mock models' demo prices: mock-gpt is $1 in, $2 out per million
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class UsageStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    UsageStore store;

    @Autowired
    JdbcClient jdbc;

    private final String client = TestEvents.newClient();

    @Test
    void anEventIsStoredPricedAndAddedToTheDay() {
        store.record(TestEvents.success(client, 1000, 500));
        store.record(TestEvents.success(client, 10, 0));

        assertThat(daily("requests")).isEqualTo(2L);
        assertThat(daily("successful_requests")).isEqualTo(2L);
        assertThat(daily("prompt_tokens")).isEqualTo(1010L);
        assertThat(daily("completion_tokens")).isEqualTo(500L);
        assertThat(cost()).isEqualByComparingTo("0.00201"); // 1010 x $1/M + 500 x $2/M
        assertThat(daily("unpriced_requests")).isEqualTo(0L);
    }

    @Test
    void aRedeliveredEventChangesNothing() {
        UsageEvent event = TestEvents.success(client, 100, 50);

        assertThat(store.record(event)).isTrue();
        Map<String, Object> before = day();
        assertThat(store.record(event)).isFalse();

        assertThat(day()).isEqualTo(before);
        assertThat(storedEvents()).isEqualTo(1);
    }

    @Test
    void theSameEventsRecordedByManyThreadsAtOnceCountOnce() throws Exception {
        List<UsageEvent> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(TestEvents.success(client, 10, 5));
        }
        try (ExecutorService threads = Executors.newFixedThreadPool(4)) {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                done.add(threads.submit(() -> events.forEach(store::record)));
            }
            for (Future<?> future : done) {
                future.get();
            }
        }

        assertThat(storedEvents()).isEqualTo(100);
        assertThat(daily("requests")).isEqualTo(100L);
        assertThat(daily("prompt_tokens")).isEqualTo(1000L);
        assertThat(cost()).isEqualByComparingTo("0.002"); // 100 x (10 x $1/M + 5 x $2/M)
    }

    @Test
    void ifTheTotalsCannotBeUpdatedTheEventIsNotStoredEither() {
        store.record(TestEvents.success(client, 1, 1));
        jdbc.sql("UPDATE usage_daily SET requests = 9223372036854775807 WHERE client_id = ?").param(client).update();

        assertThatThrownBy(() -> store.record(TestEvents.success(client, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class); // bigint out of range

        assertThat(storedEvents()).isEqualTo(1);
    }

    @Test
    void cacheHitsCostNothingAndTheirTokensAreCountedSeparately() {
        store.record(TestEvents.event(client, null, null, 70, 30, "exact", Status.SUCCESS, TestEvents.NOON));
        store.record(TestEvents.event(client, null, null, 7, 3, "semantic", Status.SUCCESS, TestEvents.NOON));

        assertThat(daily("cache_hits")).isEqualTo(2L);
        assertThat(daily("prompt_tokens")).isEqualTo(0L);
        assertThat(daily("cached_prompt_tokens")).isEqualTo(77L);
        assertThat(daily("cached_completion_tokens")).isEqualTo(33L);
        assertThat(cost()).isEqualByComparingTo("0");
        assertThat(daily("unpriced_requests")).isEqualTo(0L);
    }

    @Test
    void refusedAndFailedRequestsAreCountedButFree() {
        store.record(TestEvents.event(client, null, null, 0, 0, "none", Status.RATE_LIMITED, TestEvents.NOON));
        store.record(TestEvents.event(client, "mock-openai", "mock-gpt", 0, 0, "miss", Status.PROVIDER_ERROR,
                TestEvents.NOON));

        assertThat(daily("requests")).isEqualTo(2L);
        assertThat(daily("successful_requests")).isEqualTo(0L);
        assertThat(daily("rate_limited_requests")).isEqualTo(1L);
        assertThat(daily("failed_requests")).isEqualTo(1L);
        assertThat(cost()).isEqualByComparingTo("0");
        assertThat(daily("unpriced_requests")).isEqualTo(0L);
    }

    @Test
    void aModelWithoutAPriceIsCountedAsUnpricedNotAsFree() {
        UsageEvent event = TestEvents.event(client, "mock-openai", "no-such-model", 10, 10, "miss", Status.SUCCESS,
                TestEvents.NOON);

        store.record(event);

        assertThat(daily("unpriced_requests")).isEqualTo(1L);
        assertThat(daily("prompt_tokens")).isEqualTo(10L);
        assertThat(jdbc.sql("SELECT count(*) FROM usage_events WHERE event_id = ? AND cost_usd IS NULL")
                .param(event.eventId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theModelTheProviderWasAskedForSetsThePriceNotTheOneTheClientAskedFor() {
        // The client asked for mock-gpt ($1/$2); a failover answered with mock-claude ($3/$15).
        store.record(TestEvents.event(client, "mock-anthropic", "mock-claude", 1000, 1000, "miss", Status.SUCCESS,
                TestEvents.NOON));

        assertThat(cost()).isEqualByComparingTo("0.018");
    }

    @Test
    void eventsArePricedAsOfWhenTheyHappened() {
        String model = "m-" + UUID.randomUUID();
        price(model, "2026-01-01T00:00:00Z", "1");
        price(model, "2026-06-01T00:00:00Z", "5");
        record(model, "2025-12-31T23:59:59Z"); // before any price: unpriced
        record(model, "2026-05-31T23:59:59Z"); // $1
        record(model, "2026-06-01T00:00:00Z"); // $5, from that instant on

        assertThat(jdbc.sql("SELECT cost_usd FROM usage_events WHERE upstream_model = ? ORDER BY occurred_at")
                .param(model).query((row, n) -> String.valueOf(row.getBigDecimal(1) == null
                        ? null : row.getBigDecimal(1).stripTrailingZeros().toPlainString())).list())
                .containsExactly("null", "0.000001", "0.000005");
    }

    @Test
    void daysAreUtcDays() {
        store.record(TestEvents.event(client, "mock-openai", "mock-gpt", 1, 1, "miss", Status.SUCCESS,
                Instant.parse("2026-11-20T23:59:59.999Z")));
        store.record(TestEvents.event(client, "mock-openai", "mock-gpt", 1, 1, "miss", Status.SUCCESS,
                Instant.parse("2026-11-21T00:00:00Z")));

        assertThat(jdbc.sql("SELECT day::text FROM usage_daily WHERE client_id = ? ORDER BY day")
                .param(client).query(String.class).list()).containsExactly("2026-11-20", "2026-11-21");
    }

    @Test
    void textPostgresCannotStoreIsReportedAsBadDataNotAsAnOutage() {
        // A client chooses the model name, so it could contain a NUL character. This must reach
        // the dead-letter topic, not be retried for ever (design note 39).
        UsageEvent event = new UsageEvent(UUID.randomUUID(), client, "mock-\u0000", "mock-openai", "mock-gpt", 1, 1,
                1L, "miss", Status.SUCCESS, false, TestEvents.NOON);

        assertThatThrownBy(() -> store.record(event)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- helpers ----

    private void price(String model, String from, String inputPerMillion) {
        jdbc.sql("INSERT INTO model_prices VALUES ('p', ?, ?::timestamptz, ?::numeric, 0)")
                .param(model).param(from).param(inputPerMillion).update();
    }

    private void record(String model, String at) {
        store.record(TestEvents.event(client, "p", model, 1, 0, "miss", Status.SUCCESS, Instant.parse(at)));
    }

    private long storedEvents() {
        return jdbc.sql("SELECT count(*) FROM usage_events WHERE client_id = ?").param(client)
                .query(Long.class).single();
    }

    private Map<String, Object> day() {
        return jdbc.sql("SELECT * FROM usage_daily WHERE client_id = ?").param(client).query().singleRow();
    }

    private Object daily(String column) {
        return day().get(column);
    }

    private BigDecimal cost() {
        return (BigDecimal) daily("cost_usd");
    }
}
