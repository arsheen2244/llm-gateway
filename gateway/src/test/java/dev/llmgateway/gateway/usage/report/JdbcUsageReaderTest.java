package dev.llmgateway.gateway.usage.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The reader against PostgreSQL with the consumer's own migrations (so a schema change that
 * breaks it fails here), connected as the restricted role. Needs Docker; skipped without it.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcUsageReaderTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcUsageReader reader;

    @BeforeAll
    static void setUp() throws Exception {
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("CREATE ROLE usage_reader LOGIN PASSWORD 'reader'");
        migrate(owner, "V1__usage.sql");
        migrate(owner, "V2__reader_grant.sql");
        day(owner, "a", "2026-11-19", 1);
        day(owner, "a", "2026-11-21", 3);
        day(owner, "a", "2026-11-20", 2);
        day(owner, "b", "2026-11-20", 100);
        reader = new JdbcUsageReader(POSTGRES.getJdbcUrl(), "usage_reader", "reader");
    }

    @AfterAll
    static void close() {
        if (reader != null) {
            reader.close();
        }
    }

    @Test
    void returnsOnlyThatClientsDaysInTheRangeOldestFirst() {
        List<DailyUsage> days = reader.daily("a", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21));

        assertThat(days).extracting(DailyUsage::date)
                .containsExactly(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21));
        assertThat(days).extracting(day -> day.totals().requests()).containsExactly(2L, 3L);
    }

    @Test
    void everyColumnIsReadIntoItsOwnField() {
        UsageTotals totals = reader.daily("a", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 19)).get(0).totals();

        assertThat(totals).isEqualTo(new UsageTotals(1, 2, 3, 4, 5, 6, 7, 8, 9, totals.cost(), 11));
        assertThat(totals.cost().text()).isEqualTo("0.000000000001"); // the smallest cost stored
    }

    @Test
    void aClientWithoutUsageHasNoDays() {
        assertThat(reader.daily("nobody", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
    }

    @Test
    void theReaderRoleCanReadDailyTotalsAndNothingElse() {
        JdbcTemplate asReader = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "usage_reader", "reader"));

        assertThat(asReader.queryForObject("SELECT count(*) FROM usage_daily", Long.class)).isEqualTo(4);
        for (String forbidden : List.of("SELECT * FROM usage_events", "SELECT * FROM model_prices",
                "DELETE FROM usage_daily", "UPDATE usage_daily SET requests = 0")) {
            assertThatThrownBy(() -> asReader.execute(forbidden)).as(forbidden)
                    .rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    void aHungDatabaseIsReportedAsUnavailable() {
        var docker = POSTGRES.getDockerClient();
        docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> reader.daily("a", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 19)))
                    .isInstanceOf(UsageUnavailableException.class);
            assertThat((System.nanoTime() - start) / 1_000_000_000).isLessThan(15);
        } finally {
            docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
    }

    // ---- helpers ----

    /** The whole file as one statement batch (ScriptUtils would split the DO block at its ";"). */
    private static void migrate(JdbcTemplate owner, String file) throws Exception {
        owner.execute(new FileSystemResource("../usage-consumer/src/main/resources/db/migration/" + file)
                .getContentAsString(StandardCharsets.UTF_8));
    }

    /** Distinct numbers in every column, so a mixed-up column shows. */
    private static void day(JdbcTemplate owner, String client, String day, long requests) {
        owner.update("INSERT INTO usage_daily VALUES (?, ?::date, ?, 2, 3, 4, 5, 6, 7, 8, 9, 0.000000000001, 11)",
                client, day, requests);
    }
}
