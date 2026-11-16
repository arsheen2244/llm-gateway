package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RateLimitHeadersTest {

    private static final Limits LIMITS = Limits.perMinute(60, 40_000);

    @Test
    void allowedRequestGetsLimitsAndRemainingButNoRetryAfter() {
        Map<String, String> headers = RateLimitHeaders.of(LIMITS,
                RateLimitDecision.allowed(null, 59, 39_000));

        assertThat(headers).containsEntry("x-ratelimit-limit-requests", "60")
                .containsEntry("x-ratelimit-remaining-requests", "59")
                .containsEntry("x-ratelimit-limit-tokens", "40000")
                .containsEntry("x-ratelimit-remaining-tokens", "39000")
                .doesNotContainKey("Retry-After");
    }

    @Test
    void retryAfterIsWholeSecondsRoundedUp() {
        assertThat(retryAfter(Duration.ofMillis(1))).isEqualTo("1");
        assertThat(retryAfter(Duration.ofMillis(1000))).isEqualTo("1");
        assertThat(retryAfter(Duration.ofMillis(1001))).isEqualTo("2");
        assertThat(retryAfter(Duration.ofMillis(59_400))).isEqualTo("60");
    }

    @Test
    void tooLargeGetsNoRetryAfterBecauseRetryingCannotHelp() {
        Map<String, String> headers = RateLimitHeaders.of(LIMITS, RateLimitDecision.tooLarge(60, 40_000));

        assertThat(headers).doesNotContainKey("Retry-After");
    }

    @Test
    void negativeRemainingIsNeverShown() {
        // Reconciling up can briefly push usage past the limit; headers show 0, not a negative number.
        Map<String, String> headers = RateLimitHeaders.of(LIMITS,
                RateLimitDecision.rejected(0, -250, Duration.ofSeconds(3)));

        assertThat(headers).containsEntry("x-ratelimit-remaining-tokens", "0");
    }

    private static String retryAfter(Duration wait) {
        return RateLimitHeaders.of(LIMITS, RateLimitDecision.rejected(0, 0, wait)).get("Retry-After");
    }
}
