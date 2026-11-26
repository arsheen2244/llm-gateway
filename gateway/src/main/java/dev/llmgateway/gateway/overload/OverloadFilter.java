package dev.llmgateway.gateway.overload;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ErrorResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Load shedding: at most {@code maxInFlight} {@code /v1/} requests at once on this instance
 * (design note 45).
 *
 * <p>Every request runs on its own virtual thread, so nothing else limits how many are in flight.
 * Without this, overload piles slow requests up in the heap until it runs out (note 44). With it,
 * a request that finds no free slot is answered at once with a 503 and {@code Retry-After}, and
 * the requests already admitted keep their memory and finish.
 *
 * <p>A slot is held until the request ends. Streams are written on the request's own thread, so
 * a stream holds its slot until it finishes, which is right: an open stream holds memory too.
 *
 * <p>Runs right after {@code RequestIdFilter} (a shed request still gets an ID and an access-log
 * line) and before authentication, so turning a request away costs as little as possible. A
 * shed request never reaches the pipeline: no usage event, nothing billed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class OverloadFilter extends OncePerRequestFilter {

    static final String CODE = "gateway_overloaded";

    private final OverloadProperties properties;
    private final ObjectMapper json;
    private final Semaphore slots;
    private final Counter shed;
    private final DistributionSummary inFlightAtArrival;

    public OverloadFilter(OverloadProperties properties, ObjectMapper json, MeterRegistry registry) {
        this.properties = properties;
        this.json = json;
        this.slots = new Semaphore(properties.maxInFlight());
        Gauge.builder("gateway.requests.in.flight", this, OverloadFilter::inFlight)
                .description("Requests this instance is handling now").register(registry);
        this.shed = Counter.builder("gateway.requests.shed")
                .description("Requests turned away with 503 because too many were in flight")
                .register(registry);
        // Its _max is the peak over the last couple of minutes, which a gauge scraped every few
        // seconds would miss. Used to choose maxInFlight (note 45).
        this.inFlightAtArrival = DistributionSummary.builder("gateway.requests.in.flight.at.arrival")
                .description("Requests already in flight when a request arrived")
                .register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        inFlightAtArrival.record(inFlight());
        if (!slots.tryAcquire()) {
            shed.increment();
            reject(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }

    int inFlight() {
        return properties.maxInFlight() - slots.availablePermits();
    }

    private void reject(HttpServletResponse response) throws IOException {
        long seconds = Math.max(1, (properties.retryAfter().toMillis() + 999) / 1000);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ErrorResponse.of(
                "The gateway is handling too many requests. Retry after a short wait.", "server_error", CODE));
    }
}
