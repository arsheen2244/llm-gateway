package dev.llmgateway.gateway.usage.report;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** One client's usage on one UTC day. */
public record DailyUsage(LocalDate date, @JsonUnwrapped UsageTotals totals) {
}
