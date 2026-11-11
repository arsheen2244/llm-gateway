package dev.llmgateway.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderErrorMapperTest {

    private final ProviderErrorMapper mapper = new ProviderErrorMapper();

    @ParameterizedTest(name = "upstream {0} -> client {1}")
    @CsvSource({
            "400, 400", "404, 404", "422, 422",
            "401, 502", "403, 502",
            "429, 429",
            "500, 502", "502, 502",
            "503, 503", "529, 503"})
    void mapsUpstreamStatus(int upstream, int expected) {
        ApiError error = mapper.map(ProviderException.errorResponse("p", upstream, "msg"));

        assertThat(error.status()).isEqualTo(expected);
    }

    @Test
    void clientErrorsKeepTheProviderMessage() {
        ApiError error = mapper.map(ProviderException.errorResponse("p", 400, "max_tokens is too large"));

        assertThat(error.body().error().message()).isEqualTo("max_tokens is too large");
    }

    @Test
    void authErrorsHideTheProviderMessageBecauseItMayContainOurKey() {
        ApiError error = mapper.map(ProviderException.errorResponse("p", 401, "Incorrect API key provided: sk-abc...xyz"));

        assertThat(error.body().error().message()).doesNotContain("sk-");
        assertThat(error.body().error().code()).isEqualTo("provider_auth_failed");
    }

    @Test
    void timeoutIs504() {
        assertThat(mapper.map(ProviderException.of("p", Reason.TIMEOUT, "t", null)).status()).isEqualTo(504);
    }

    @Test
    void ioErrorAndInvalidResponseAre502() {
        assertThat(mapper.map(ProviderException.of("p", Reason.IO_ERROR, "x", null)).status()).isEqualTo(502);
        assertThat(mapper.map(ProviderException.of("p", Reason.INVALID_RESPONSE, "x", null)).status()).isEqualTo(502);
    }

    @Test
    void missingKeyIs503() {
        assertThat(mapper.map(ProviderException.of("p", Reason.NOT_CONFIGURED, "x", null)).status()).isEqualTo(503);
    }
}
