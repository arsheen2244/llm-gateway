package dev.llmgateway.gateway.cache;

/** The embedding service failed; the semantic layer is skipped for this request. */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
