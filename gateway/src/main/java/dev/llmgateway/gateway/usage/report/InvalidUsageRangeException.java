package dev.llmgateway.gateway.usage.report;

/** The dates asked for make no sense, or cover too much. The message is shown to the client. */
public class InvalidUsageRangeException extends RuntimeException {

    public InvalidUsageRangeException(String message) {
        super(message);
    }
}
