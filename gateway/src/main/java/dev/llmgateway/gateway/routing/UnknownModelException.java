package dev.llmgateway.gateway.routing;

/** No route matches the requested model. Becomes a 404, like OpenAI's "model_not_found". */
public class UnknownModelException extends RuntimeException {

    public UnknownModelException(String model) {
        super("The model '" + model + "' is not served by this gateway");
    }
}
