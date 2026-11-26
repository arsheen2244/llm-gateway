package dev.llmgateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ModelPriceTest {

    @Test
    void costIsTokensTimesPricePerMillion() {
        ModelPrice price = new ModelPrice(new BigDecimal("1.00"), new BigDecimal("2.00"));

        assertThat(price.cost(1_000_000, 500_000)).isEqualByComparingTo("2.00");
        assertThat(price.cost(10, 20)).isEqualByComparingTo("0.00005");
        assertThat(price.cost(0, 0)).isEqualByComparingTo("0");
    }

    @Test
    void tinyCostsAddUpWithoutRoundingError() {
        ModelPrice price = new ModelPrice(new BigDecimal("0.10"), BigDecimal.ZERO);

        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 1_000_000; i++) {
            total = total.add(price.cost(1, 0));
        }

        assertThat(total).isEqualByComparingTo("0.10"); // with doubles this drifts
    }
}
