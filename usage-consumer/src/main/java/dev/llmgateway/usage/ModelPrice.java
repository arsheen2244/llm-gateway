package dev.llmgateway.usage;

import java.math.BigDecimal;

/** A model's price in US dollars per million tokens. */
public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

    /** Exact decimal arithmetic: moving the point six places is dividing by a million. */
    public BigDecimal cost(int promptTokens, int completionTokens) {
        return inputPerMillion.multiply(BigDecimal.valueOf(promptTokens))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(completionTokens)))
                .movePointLeft(6);
    }
}
