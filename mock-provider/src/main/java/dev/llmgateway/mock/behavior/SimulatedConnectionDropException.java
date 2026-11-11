package dev.llmgateway.mock.behavior;

/**
 * Thrown in the middle of a stream to simulate the provider's connection dying.
 *
 * <p>The response is already committed, so Spring cannot turn this into an error
 * response. Tomcat aborts the connection instead, without sending the final chunk
 * of the chunked body, and the client sees a broken stream.
 *
 * <p>No stack trace is captured: the failure is intentional, and load tests
 * trigger it often, so full traces would flood the logs.
 */
public class SimulatedConnectionDropException extends RuntimeException {

    public SimulatedConnectionDropException(int chunksSent) {
        super("Simulated connection drop after " + chunksSent + " content chunks", null, false, false);
    }
}
