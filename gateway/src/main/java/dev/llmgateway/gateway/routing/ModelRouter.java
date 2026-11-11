package dev.llmgateway.gateway.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.llmgateway.gateway.config.GatewayProperties;
import dev.llmgateway.gateway.config.GatewayProperties.Route;
import org.springframework.stereotype.Component;

/**
 * Maps a model name to a provider instance using an ordered list of rules:
 * the first matching rule wins (design note 8). Put specific rules such as
 * "mock-claude-*" before general ones such as "mock-*".
 */
@Component
public class ModelRouter {

    private final List<Route> routes;
    private final Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public ModelRouter(GatewayProperties properties) {
        properties.providers().forEach((name, p) ->
                providers.put(name, new ProviderConfig(name, p.type(), stripTrailingSlash(p.baseUrl()), p.apiKey())));
        this.routes = List.copyOf(properties.routes());
        // Fail at startup, not on the first request, if a route points nowhere.
        for (Route route : routes) {
            if (!providers.containsKey(route.provider())) {
                throw new IllegalStateException("Route '" + route.modelPattern()
                        + "' refers to unknown provider '" + route.provider() + "'");
            }
        }
    }

    public ProviderConfig route(String model) {
        for (Route route : routes) {
            if (matches(route.modelPattern(), model)) {
                return providers.get(route.provider());
            }
        }
        throw new UnknownModelException(model);
    }

    /** All configured provider instances (used for startup checks). */
    public Iterable<ProviderConfig> providers() {
        return providers.values();
    }

    static boolean matches(String pattern, String model) {
        if (pattern.endsWith("*")) {
            return model.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(model);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
