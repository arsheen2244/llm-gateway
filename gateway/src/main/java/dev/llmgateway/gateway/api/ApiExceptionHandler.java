package dev.llmgateway.gateway.api;

import java.util.stream.Collectors;

import dev.llmgateway.gateway.api.dto.ErrorResponse;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.ratelimit.RateLimitExceededException;
import dev.llmgateway.gateway.routing.UnknownModelException;
import dev.llmgateway.gateway.usage.report.InvalidUsageRangeException;
import dev.llmgateway.gateway.usage.report.UsageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into OpenAI-style error responses, so clients built for
 * OpenAI can parse every error the gateway returns.
 *
 * <p>This only covers errors that happen before a response has started.
 * Streaming errors after the first byte are handled in the streaming code,
 * because by then the HTTP status has already been sent.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProviderErrorMapper mapper;

    public ApiExceptionHandler(ProviderErrorMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ErrorResponse> providerFailure(ProviderException e) {
        log.warn("Provider call failed: provider={} reason={} upstreamStatus={} message={}",
                e.provider(), e.reason(), e.upstreamStatus(), e.getMessage());
        return toResponse(mapper.map(e));
    }

    /** 429 with the limiter's headers, so the client knows its limits and when to retry. */
    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ErrorResponse> rateLimited(RateLimitExceededException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(429).contentType(MediaType.APPLICATION_JSON);
        e.headers().forEach(response::header);
        return response.body(ErrorResponse.of(e.getMessage(), "rate_limit_error", e.code()));
    }

    @ExceptionHandler(UnknownModelException.class)
    ResponseEntity<ErrorResponse> unknownModel(UnknownModelException e) {
        return toResponse(ApiError.of(404, e.getMessage(), "invalid_request_error", "model_not_found"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidRequest(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return toResponse(ApiError.of(400, message, "invalid_request_error", "invalid_request"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        return toResponse(ApiError.of(400, "Request body is not valid JSON for this endpoint",
                "invalid_request_error", "invalid_json"));
    }

    @ExceptionHandler(InvalidUsageRangeException.class)
    ResponseEntity<ErrorResponse> invalidUsageRange(InvalidUsageRangeException e) {
        return toResponse(ApiError.of(400, e.getMessage(), "invalid_request_error", "invalid_request"));
    }

    /** A query parameter of the wrong type, e.g. a date that isn't one. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidParameter(MethodArgumentTypeMismatchException e) {
        return toResponse(ApiError.of(400, "Invalid value for '" + e.getName() + "'; dates look like 2026-11-24",
                "invalid_request_error", "invalid_request"));
    }

    @ExceptionHandler(UsageUnavailableException.class)
    ResponseEntity<ErrorResponse> usageUnavailable(UsageUnavailableException e) {
        log.warn("Usage report unavailable: {}", e.getMessage(), e.getCause());
        return toResponse(ApiError.of(503, "Usage data is not available right now", "server_error",
                "usage_unavailable"));
    }

    /**
     * The content type is set explicitly. A streaming client may send
     * {@code Accept: text/event-stream}, and without this Spring would refuse to
     * write a JSON error ("not acceptable") instead of returning the real error.
     */
    private static ResponseEntity<ErrorResponse> toResponse(ApiError error) {
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON).body(error.body());
    }
}
