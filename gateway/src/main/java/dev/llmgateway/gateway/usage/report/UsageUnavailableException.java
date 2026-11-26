package dev.llmgateway.gateway.usage.report;

/** The usage database is down, or none is configured. */
public class UsageUnavailableException extends RuntimeException {

    public UsageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
