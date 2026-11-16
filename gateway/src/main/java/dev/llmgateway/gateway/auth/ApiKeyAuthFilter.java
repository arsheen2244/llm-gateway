package dev.llmgateway.gateway.auth;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ErrorResponse;
import dev.llmgateway.gateway.observability.LogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Checks the gateway API key on every {@code /v1/} request, before the body is read.
 *
 * <p>Clients send it the way OpenAI SDKs already do: {@code Authorization: Bearer <key>}.
 * A missing or unknown key gets a 401 in OpenAI's error format. A valid one attaches the
 * {@link ClientIdentity} to the request for the pipeline, and adds the client id to the
 * logging context.
 *
 * <p>This is a servlet filter, not a pipeline interceptor, because it needs nothing from
 * the parsed body and should reject unauthenticated traffic as cheaply as possible
 * (design note 10). It runs right after {@code RequestIdFilter}, so rejections still get
 * a request ID and an access-log line.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ClientRegistry clients;
    private final ObjectMapper json;

    public ApiKeyAuthFilter(ClientRegistry clients, ObjectMapper json) {
        this.clients = clients;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, "Missing API key. Send it as 'Authorization: Bearer <key>'.", "missing_api_key");
            return;
        }
        Optional<ClientIdentity> client = clients.authenticate(header.substring(BEARER.length()).strip());
        if (client.isEmpty()) {
            reject(response, "Invalid API key.", "invalid_api_key");
            return;
        }
        request.setAttribute(ClientIdentity.REQUEST_ATTRIBUTE, client.get());
        MDC.put(LogFields.CLIENT_ID, client.get().id());
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ErrorResponse.of(message, "invalid_request_error", code));
    }
}
