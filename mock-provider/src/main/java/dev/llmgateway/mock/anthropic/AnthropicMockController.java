package dev.llmgateway.mock.anthropic;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.mock.anthropic.AnthropicWire.ContentBlockDelta;
import dev.llmgateway.mock.anthropic.AnthropicWire.ContentBlockStart;
import dev.llmgateway.mock.anthropic.AnthropicWire.ContentBlockStop;
import dev.llmgateway.mock.anthropic.AnthropicWire.ErrorBody;
import dev.llmgateway.mock.anthropic.AnthropicWire.ErrorDetail;
import dev.llmgateway.mock.anthropic.AnthropicWire.Message;
import dev.llmgateway.mock.anthropic.AnthropicWire.MessageDelta;
import dev.llmgateway.mock.anthropic.AnthropicWire.MessageStart;
import dev.llmgateway.mock.anthropic.AnthropicWire.MessagesRequest;
import dev.llmgateway.mock.anthropic.AnthropicWire.OutputUsage;
import dev.llmgateway.mock.anthropic.AnthropicWire.StopInfo;
import dev.llmgateway.mock.anthropic.AnthropicWire.TextBlock;
import dev.llmgateway.mock.anthropic.AnthropicWire.TextDelta;
import dev.llmgateway.mock.anthropic.AnthropicWire.TypeOnly;
import dev.llmgateway.mock.anthropic.AnthropicWire.Usage;
import dev.llmgateway.mock.behavior.FakeCompletionGenerator;
import dev.llmgateway.mock.behavior.MockBehaviorProperties;
import dev.llmgateway.mock.behavior.Pause;
import dev.llmgateway.mock.behavior.Scenario;
import dev.llmgateway.mock.behavior.ScenarioResolver;
import dev.llmgateway.mock.behavior.SimulatedConnectionDropException;
import dev.llmgateway.mock.sse.SseWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Fake {@code POST /v1/messages} in Anthropic's format, normal and streaming. */
@RestController
public class AnthropicMockController {

    private final ScenarioResolver scenarios;
    private final FakeCompletionGenerator generator;
    private final MockBehaviorProperties properties;
    private final ObjectMapper json;

    public AnthropicMockController(ScenarioResolver scenarios, FakeCompletionGenerator generator,
                                   MockBehaviorProperties properties, ObjectMapper json) {
        this.scenarios = scenarios;
        this.generator = generator;
        this.properties = properties;
        this.json = json;
    }

    @PostMapping("/v1/messages")
    public ResponseEntity<?> messages(
            @RequestHeader(name = "x-api-key", required = false) String apiKey,
            @RequestHeader(name = "anthropic-version", required = false) String version,
            @RequestBody MessagesRequest request,
            HttpServletResponse response) throws IOException {
        // Check the same things the real API checks, so adapter bugs show up here.
        if (apiKey == null || apiKey.isBlank()) {
            return error(401, "x-api-key header is required");
        }
        if (version == null || version.isBlank()) {
            return error(400, "anthropic-version header is required");
        }
        if (request.maxTokens() == null) {
            return error(400, "max_tokens: Field required");
        }

        Scenario scenario = scenarios.resolve(request.model());
        Pause.millis(properties.latencyMs());

        if (scenario.kind() == Scenario.Kind.HTTP_ERROR) {
            return error(scenario.httpStatus(), "Simulated provider error (HTTP " + scenario.httpStatus() + ")");
        }
        if (!request.isStream()) {
            return scenario.kind() == Scenario.Kind.FAIL_MID_STREAM
                    ? error(500, "Simulated provider error (HTTP 500)")
                    : ResponseEntity.ok(message(request));
        }
        stream(request, scenario, response);
        return null;
    }

    private Message message(MessagesRequest request) {
        String text = String.join("", generator.chunks(properties.chunkCount()));
        return new Message(newId(), "message", "assistant", request.model(), List.of(TextBlock.of(text)),
                "end_turn", null, new Usage(inputTokens(request), properties.chunkCount()));
    }

    /**
     * Anthropic's event sequence: message_start, content_block_start, ping,
     * content_block_delta (repeated), content_block_stop, message_delta, message_stop.
     */
    private void stream(MessagesRequest request, Scenario scenario, HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        SseWriter sse = new SseWriter(response.getOutputStream(), json);

        Message start = new Message(newId(), "message", "assistant", request.model(), List.of(),
                null, null, new Usage(inputTokens(request), 1));
        sse.event("message_start", new MessageStart("message_start", start));
        sse.event("content_block_start", new ContentBlockStart("content_block_start", 0, TextBlock.of("")));
        sse.event("ping", new TypeOnly("ping"));

        List<String> pieces = generator.chunks(properties.chunkCount());
        for (int i = 0; i < pieces.size(); i++) {
            dropIfDue(scenario, i);
            Pause.millis(properties.chunkDelayMs());
            sse.event("content_block_delta", new ContentBlockDelta("content_block_delta", 0,
                    new TextDelta("text_delta", pieces.get(i))));
        }
        dropIfDue(scenario, pieces.size());

        sse.event("content_block_stop", new ContentBlockStop("content_block_stop", 0));
        sse.event("message_delta", new MessageDelta("message_delta",
                new StopInfo("end_turn", null), new OutputUsage(pieces.size())));
        sse.event("message_stop", new TypeOnly("message_stop"));
    }

    private static void dropIfDue(Scenario scenario, int index) {
        if (scenario.shouldDropBefore(index)) {
            throw new SimulatedConnectionDropException(index);
        }
    }

    private int inputTokens(MessagesRequest request) {
        StringBuilder text = new StringBuilder();
        if (request.system() != null) {
            text.append(request.system().asText(""));
        }
        if (request.messages() != null) {
            for (JsonNode message : request.messages()) {
                text.append(message.path("content").asText(""));
            }
        }
        return generator.estimateTokens(text.toString());
    }

    private static ResponseEntity<ErrorBody> error(int status, String message) {
        // Explicit content type: real providers send JSON errors even to "Accept: text/event-stream".
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody("error", new ErrorDetail(errorType(status), message)));
    }

    /** Anthropic's documented error types. 529 means "overloaded". */
    private static String errorType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            default -> "api_error";
        };
    }

    private static String newId() {
        return "msg_mock_" + UUID.randomUUID().toString().replace("-", "");
    }
}
