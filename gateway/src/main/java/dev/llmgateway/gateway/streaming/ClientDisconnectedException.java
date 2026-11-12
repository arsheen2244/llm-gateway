package dev.llmgateway.gateway.streaming;

/**
 * Writing to the client failed, almost always because the client closed the
 * connection. It is unchecked so it can travel up through the adapter's read
 * loop (which then closes the provider connection) without every layer
 * declaring it.
 */
public class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(Throwable cause) {
        super("Client disconnected", cause);
    }
}
