package dev.llmgateway.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.observability.LogFields;
import dev.llmgateway.gateway.pipeline.ChatPipeline;
import dev.llmgateway.gateway.pipeline.RequestOptions;
import dev.llmgateway.gateway.pipeline.ResponseHeaders;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.streaming.ClientDisconnectedException;
import dev.llmgateway.gateway.streaming.SseResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Serves a streaming request on the request's own (virtual) thread: read from
 * the provider, write to the client, repeat. How failures are handled depends
 * on timing (design note 12):
 * <ul>
 *   <li><b>Before the first byte:</b> rethrow, so {@link ApiExceptionHandler}
 *       sends a normal HTTP error with the right status code.</li>
 *   <li><b>After the first byte:</b> the 200 status is already sent, so send an
 *       error event, {@code data: {"error": {...}}}, and end the response
 *       without {@code [DONE]}. OpenAI's client libraries raise an exception
 *       when they see this.</li>
 *   <li><b>Client disconnected:</b> nothing left to tell anyone. Log it. The
 *       adapter has already closed the provider connection.</li>
 * </ul>
 */
@Component
public class StreamingChatHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatHandler.class);

    private final ChatPipeline pipeline;
    private final ProviderErrorMapper errorMapper;
    private final ObjectMapper json;

    public StreamingChatHandler(ChatPipeline pipeline, ProviderErrorMapper errorMapper, ObjectMapper json) {
        this.pipeline = pipeline;
        this.errorMapper = errorMapper;
        this.json = json;
    }

    public void handle(ChatCompletionRequest request, ClientIdentity client, RequestOptions options,
                       HttpServletResponse response) {
        ResponseHeaders headers = new ResponseHeaders();
        SseResponse sse = new SseResponse(response, json, headers);
        try {
            pipeline.stream(request, client, headers, options, chunk -> {
                // The usage-only chunk is always fetched (for accounting) but only
                // forwarded if the client asked for it, as OpenAI itself behaves.
                if (chunk.usageOnly() && !request.usageRequested()) {
                    return;
                }
                sse.send(chunk);
            });
            sse.done();
            MDC.put(LogFields.STREAM_OUTCOME, "completed");
        } catch (ClientDisconnectedException e) {
            MDC.put(LogFields.STREAM_OUTCOME, "client_disconnected");
            log.info("Client disconnected mid-stream; provider stream closed");
        } catch (RuntimeException e) {
            if (!sse.started()) {
                throw e;
            }
            // The access log will still say status 200, so record what really happened.
            MDC.put(LogFields.STREAM_OUTCOME, "interrupted");
            sendErrorEvent(sse, e);
        }
    }

    private void sendErrorEvent(SseResponse sse, RuntimeException failure) {
        ApiError error;
        if (failure instanceof ProviderException providerFailure) {
            log.warn("Stream failed after it started: provider={} reason={} message={}",
                    providerFailure.provider(), providerFailure.reason(), providerFailure.getMessage());
            error = errorMapper.map(providerFailure);
        } else {
            log.error("Unexpected error after stream started", failure);
            error = ApiError.of(500, "Internal error while streaming", "server_error", "internal_error");
        }
        try {
            sse.send(error.body());
        } catch (ClientDisconnectedException ignored) {
            // The client left at the same moment. Nothing more to do.
        }
    }
}
