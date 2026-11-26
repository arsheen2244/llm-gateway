package dev.llmgateway.gateway.usage.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Builds a client's usage report: checks the dates, reads the days, adds them up. */
public class UsageReports {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2999, 12, 31);

    private final UsageReader reader;
    private final Clock clock;

    public UsageReports(UsageReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * @param from first UTC day; null: 30 days ending at {@code to}
     * @param to   last UTC day; null: today
     */
    public UsageReport report(String clientId, LocalDate from, LocalDate to) {
        LocalDate last = to != null ? checked(to) : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate first = from != null ? checked(from) : last.minusDays(DEFAULT_DAYS - 1);
        if (first.isAfter(last)) {
            throw new InvalidUsageRangeException("'from' (" + first + ") is after 'to' (" + last + ")");
        }
        if (first.plusDays(MAX_DAYS).isBefore(last.plusDays(1))) {
            throw new InvalidUsageRangeException("At most " + MAX_DAYS + " days per request");
        }
        List<DailyUsage> days = reader.daily(clientId, first, last);
        UsageTotals total = days.stream().map(DailyUsage::totals).reduce(UsageTotals.NONE, UsageTotals::plus);
        return new UsageReport("usage", clientId, first, last, total, days);
    }

    /** Dates the database can't hold would look like an outage. */
    private static LocalDate checked(LocalDate date) {
        if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
            throw new InvalidUsageRangeException("Dates must be between " + EARLIEST + " and " + LATEST);
        }
        return date;
    }
}
