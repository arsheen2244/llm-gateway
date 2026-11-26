package dev.llmgateway.gateway.overload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OverloadFilterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    /** Room for one request at a time, so a second one arriving during the first is shed. */
    private final OverloadFilter filter = new OverloadFilter(
            new OverloadProperties(true, 1, Duration.ofMillis(1500)), json, registry);

    @Test
    void requestWithAFreeSlotPassesAndReleasesIt() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chatRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull(); // the chain continued
        assertThat(filter.inFlight()).isZero();
    }

    @Test
    void requestWhileFullIsShedWith503AndRetryAfter() throws Exception {
        AtomicReference<MockHttpServletResponse> second = new AtomicReference<>();
        AtomicReference<MockFilterChain> secondChain = new AtomicReference<>();
        // The second request arrives while the first is still in its chain.
        FilterChain occupying = (req, res) -> {
            second.set(new MockHttpServletResponse());
            secondChain.set(new MockFilterChain());
            filter.doFilter(chatRequest(), second.get(), secondChain.get());
        };

        filter.doFilter(chatRequest(), new MockHttpServletResponse(), occupying);

        assertThat(secondChain.get().getRequest()).isNull(); // never reached the pipeline
        MockHttpServletResponse response = second.get();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2"); // 1.5 s, rounded up
        JsonNode error = json.readTree(response.getContentAsString()).get("error");
        assertThat(error.get("type").asText()).isEqualTo("server_error");
        assertThat(error.get("code").asText()).isEqualTo(OverloadFilter.CODE);
        assertThat(registry.get("gateway.requests.shed").counter().count()).isEqualTo(1);
        assertThat(filter.inFlight()).isZero(); // the first one finished and released its slot
    }

    @Test
    void slotIsReleasedWhenTheRequestThrows() throws Exception {
        FilterChain failing = (req, res) -> {
            throw new IOException("client went away");
        };

        assertThatThrownBy(() -> filter.doFilter(chatRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(IOException.class);

        assertThat(filter.inFlight()).isZero();
        MockFilterChain next = new MockFilterChain();
        filter.doFilter(chatRequest(), new MockHttpServletResponse(), next);
        assertThat(next.getRequest()).isNotNull();
    }

    @Test
    void gaugeShowsRequestsInFlight() throws Exception {
        AtomicReference<Double> during = new AtomicReference<>();
        FilterChain looking = (req, res) -> during.set(registry.get("gateway.requests.in.flight").gauge().value());

        filter.doFilter(chatRequest(), new MockHttpServletResponse(), looking);

        assertThat(during.get()).isEqualTo(1.0);
        assertThat(registry.get("gateway.requests.in.flight").gauge().value()).isZero();
        assertThat(registry.get("gateway.requests.in.flight.at.arrival").summary().count()).isEqualTo(1);
    }

    @Test
    void pathsOutsideV1AreNeverShed() throws Exception {
        AtomicReference<MockFilterChain> inner = new AtomicReference<>(new MockFilterChain());
        FilterChain occupying = (req, res) ->
                filter.doFilter(new MockHttpServletRequest("GET", "/error"), new MockHttpServletResponse(), inner.get());

        filter.doFilter(chatRequest(), new MockHttpServletResponse(), occupying);

        assertThat(inner.get().getRequest()).isNotNull();
    }

    @Test
    void disabledFilterLetsEverythingThrough() throws Exception {
        OverloadFilter off = new OverloadFilter(
                new OverloadProperties(false, 1, Duration.ofSeconds(1)), json, new SimpleMeterRegistry());
        MockFilterChain inner = new MockFilterChain();
        FilterChain occupying = (req, res) -> off.doFilter(chatRequest(), new MockHttpServletResponse(), inner);

        off.doFilter(chatRequest(), new MockHttpServletResponse(), occupying);

        assertThat(inner.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest chatRequest() {
        return new MockHttpServletRequest("POST", "/v1/chat/completions");
    }
}
