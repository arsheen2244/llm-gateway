package dev.llmgateway.gateway.usage.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import dev.llmgateway.gateway.support.FakeClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Date rules and adding up. The fake clock says 2023-11-14, 22:13 UTC. */
class UsageReportsTest {

    private static final LocalDate TODAY = LocalDate.of(2023, 11, 14);

    private String askedClient;
    private LocalDate askedFrom;
    private LocalDate askedTo;
    private List<DailyUsage> stored = List.of();

    private final UsageReports reports = new UsageReports((clientId, from, to) -> {
        askedClient = clientId;
        askedFrom = from;
        askedTo = to;
        return stored;
    }, new FakeClock());

    @Test
    void defaultsToTheLastThirtyDaysIncludingToday() {
        UsageReport report = reports.report("c", null, null);

        assertThat(askedClient).isEqualTo("c");
        assertThat(askedFrom).isEqualTo(LocalDate.of(2023, 10, 16));
        assertThat(askedTo).isEqualTo(TODAY);
        assertThat(report.total()).isEqualTo(UsageTotals.NONE);
    }

    @Test
    void onlyToMeansTheThirtyDaysEndingThere() {
        reports.report("c", null, LocalDate.of(2023, 3, 30));

        assertThat(askedFrom).isEqualTo(LocalDate.of(2023, 3, 1));
    }

    @Test
    void totalIsTheExactSumOfTheDays() {
        stored = List.of(new DailyUsage(TODAY.minusDays(1), totals(1, "0.1")),
                new DailyUsage(TODAY, totals(2, "0.2")));

        UsageTotals total = reports.report("c", null, null).total();

        assertThat(total).isEqualTo(new UsageTotals(3, 3, 3, 3, 3, 3, 3, 3, 3, total.cost(), 3));
        assertThat(total.cost().text()).isEqualTo("0.3"); // doubles give 0.30000000000000004
    }

    @ParameterizedTest
    @CsvSource({"2023-01-02,2024-01-02", "2024-01-01,2024-12-31", "2023-05-05,2023-05-05"})
    void upTo366DaysAreAllowed(LocalDate from, LocalDate to) {
        reports.report("c", from, to);

        assertThat(askedFrom).isEqualTo(from);
        assertThat(askedTo).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({"2023-01-01,2024-01-02", "2023-05-06,2023-05-05", "1999-12-31,2000-01-01",
            "2999-12-31,3000-01-01", "-999999999-01-01,-999999999-01-01", ",-999999999-01-01"})
    void badRangesAreRefused(LocalDate from, LocalDate to) {
        assertThatThrownBy(() -> reports.report("c", from, to)).isInstanceOf(InvalidUsageRangeException.class);
        assertThat(askedClient).as("the database is not asked").isNull();
    }

    static UsageTotals totals(long n, String cost) {
        return new UsageTotals(n, n, n, n, n, n, n, n, n, new PlainDecimal(new BigDecimal(cost)), n);
    }
}
