package dev.llmgateway.gateway.api;

import java.util.stream.Collectors;

import dev.llmgateway.gateway.api.dto.ErrorResponse;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.routing.UnknownModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private static ResponseEntity<ErrorResponse> toResponse(ApiError error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }
}
