package dev.llmgateway.gateway.api.dto;

/** OpenAI's error body: {@code {"error": {"message", "type", "param", "code"}}}. */
public record ErrorResponse(Detail error) {

    public record Detail(String message, String type, String param, String code) {
    }

    public static ErrorResponse of(String message, String type, String code) {
        return new ErrorResponse(new Detail(message, type, null, code));
    }
}
