package dev.llmgateway.gateway.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID and writes one access-log line when it finishes.
 *
 * <p>The ID goes into the MDC (a per-thread map the logger attaches to every log
 * line), so all logs written while handling the request carry it, and it is
 * echoed in the {@code X-Request-Id} response header so a client can quote it
 * in a bug report. Runs first, so the ID exists before anything else logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /**
     * A client-supplied ID is used only if it is short and plain. It ends up in
     * logs, and a value with newlines could forge fake log lines.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Logger accessLog = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveId(request.getHeader(HEADER));
        long start = System.nanoTime();
        MDC.put(LogFields.REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            accessLog.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", durationMs)
                    .log("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(),
                            response.getStatus(), durationMs);
            // The thread is ours for this request only, but clear the map anyway
            // so nothing can leak into unrelated work.
            MDC.clear();
        }
    }

    static String resolveId(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
