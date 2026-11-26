package dev.llmgateway.gateway.usage.report;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The answer to {@code GET /v1/usage}.
 *
 * @param days only days with any usage, oldest first
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageReport(String object, String clientId, LocalDate from, LocalDate to, UsageTotals total,
                          List<DailyUsage> days) {
}
