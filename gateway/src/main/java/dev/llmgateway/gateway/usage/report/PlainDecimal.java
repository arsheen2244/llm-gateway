package dev.llmgateway.gateway.usage.report;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonValue;

/** An exact decimal, written to JSON as text without trailing zeros or an exponent. */
public record PlainDecimal(BigDecimal value) {

    @JsonValue
    public String text() {
        return value.stripTrailingZeros().toPlainString();
    }
}
