package dev.llmgateway.mock.openai;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.mock.behavior.FakeCompletionGenerator;
import dev.llmgateway.mock.behavior.MockBehaviorProperties;
import dev.llmgateway.mock.behavior.Pause;
import dev.llmgateway.mock.behavior.Scenario;
import dev.llmgateway.mock.behavior.ScenarioResolver;
import dev.llmgateway.mock.behavior.SimulatedConnectionDropException;
import dev.llmgateway.mock.openai.OpenAiWire.ChatCompletion;
import dev.llmgateway.mock.openai.OpenAiWire.ChatRequest;
import dev.llmgateway.mock.openai.OpenAiWire.Choice;
import dev.llmgateway.mock.openai.OpenAiWire.Chunk;
import dev.llmgateway.mock.openai.OpenAiWire.ChunkChoice;
import dev.llmgateway.mock.openai.OpenAiWire.Delta;
import dev.llmgateway.mock.openai.OpenAiWire.ErrorBody;
import dev.llmgateway.mock.openai.OpenAiWire.ErrorDetail;
import dev.llmgateway.mock.openai.OpenAiWire.Message;
import dev.llmgateway.mock.openai.OpenAiWire.Usage;
import dev.llmgateway.mock.sse.SseWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Fake {@code POST /v1/chat/completions} in OpenAI's format, normal and streaming. */
@RestController
public class OpenAiMockController {

    private final ScenarioResolver scenarios;
    private final FakeCompletionGenerator generator;
    private final MockBehaviorProperties properties;
    private final ObjectMapper json;

    public OpenAiMockController(ScenarioResolver scenarios, FakeCompletionGenerator generator,
                                MockBehaviorProperties properties, ObjectMapper json) {
        this.scenarios = scenarios;
        this.generator = generator;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Returns a ResponseEntity for normal responses and errors. For streams it writes
     * to the servlet response directly and returns null, which tells Spring the
     * response has already been handled.
     */
    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chatCompletions(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody ChatRequest request,
            HttpServletResponse response) throws IOException {
        // Like the real API: no bearer token, no answer. Catches adapters that forget auth.
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return error(401);
        }
        Scenario scenario = scenarios.resolve(request.model());
        Pause.millis(properties.latencyMs());

        if (scenario.kind() == Scenario.Kind.HTTP_ERROR) {
            return error(scenario.httpStatus());
        }
        if (!request.isStream()) {
            // A non-streaming response has no "middle" to fail in, so a mid-stream
            // scenario becomes a plain 500 here.
            return scenario.kind() == Scenario.Kind.FAIL_MID_STREAM ? error(500) : ResponseEntity.ok(completion(request));
        }
        stream(request, scenario, response);
        return null;
    }

    private ChatCompletion completion(ChatRequest request) {
        String text = String.join("", generator.chunks(properties.chunkCount()));
        Choice choice = new Choice(0, new Message("assistant", text), "stop");
        return new ChatCompletion(newId(), "chat.completion", now(), request.model(),
                List.of(choice), usage(request));
    }

    private void stream(ChatRequest request, Scenario scenario, HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        SseWriter sse = new SseWriter(response.getOutputStream(), json);

        String id = newId();
        long created = now();
        // The first chunk announces the role. Content follows in later chunks.
        sse.data(chunk(id, created, request.model(), new Delta("assistant", ""), null));

        List<String> pieces = generator.chunks(properties.chunkCount());
        for (int i = 0; i < pieces.size(); i++) {
            dropIfDue(scenario, i);
            Pause.millis(properties.chunkDelayMs());
            sse.data(chunk(id, created, request.model(), new Delta(null, pieces.get(i)), null));
        }
        dropIfDue(scenario, pieces.size());

        sse.data(chunk(id, created, request.model(), new Delta(null, null), "stop"));
        if (request.includeUsage()) {
            // Real OpenAI sends usage only when asked, in a final chunk with no choices.
            sse.data(new Chunk(id, "chat.completion.chunk", created, request.model(), List.of(), usage(request)));
        }
        sse.rawData("[DONE]");
    }

    private static void dropIfDue(Scenario scenario, int index) {
        if (scenario.shouldDropBefore(index)) {
            throw new SimulatedConnectionDropException(index);
        }
    }

    private static Chunk chunk(String id, long created, String model, Delta delta, String finishReason) {
        return new Chunk(id, "chat.completion.chunk", created, model,
                List.of(new ChunkChoice(0, delta, finishReason)), null);
    }

    private Usage usage(ChatRequest request) {
        int prompt = generator.estimateTokens(promptText(request));
        int completion = properties.chunkCount(); // about one token per chunk
        return new Usage(prompt, completion, prompt + completion);
    }

    private static String promptText(ChatRequest request) {
        StringBuilder text = new StringBuilder();
        if (request.messages() != null) {
            for (JsonNode message : request.messages()) {
                text.append(message.path("content").asText(""));
            }
        }
        return text.toString();
    }

    private static ResponseEntity<ErrorBody> error(int status) {
        ErrorDetail detail = new ErrorDetail("Simulated provider error (HTTP " + status + ")",
                errorType(status), null, null);
        // Explicit content type: real providers send JSON errors even to "Accept: text/event-stream".
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(detail));
    }

    private static String errorType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 429 -> "rate_limit_error";
            default -> "server_error";
        };
    }

    private static String newId() {
        return "chatcmpl-mock-" + UUID.randomUUID();
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
