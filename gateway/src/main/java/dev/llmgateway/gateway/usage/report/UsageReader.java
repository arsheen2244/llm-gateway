package dev.llmgateway.gateway.usage.report;

import java.time.LocalDate;
import java.util.List;

/** Reads the daily totals the usage consumer maintains (design note 39). */
public interface UsageReader {

    /**
     * One client's days with usage, oldest first; both dates included.
     *
     * @throws UsageUnavailableException if the totals can't be read now
     */
    List<DailyUsage> daily(String clientId, LocalDate from, LocalDate to);
}
