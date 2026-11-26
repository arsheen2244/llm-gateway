package dev.llmgateway.usage;

/** A message that is not a valid usage event. Trying again cannot help. */
public class InvalidUsageEventException extends RuntimeException {

    public InvalidUsageEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
