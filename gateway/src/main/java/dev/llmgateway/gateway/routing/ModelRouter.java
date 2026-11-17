package dev.llmgateway.gateway.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.llmgateway.gateway.config.GatewayProperties;
import dev.llmgateway.gateway.config.GatewayProperties.Route;
import dev.llmgateway.gateway.config.GatewayProperties.Target;
import org.springframework.stereotype.Component;

/**
 * Maps a model name to the providers that serve it, using an ordered list of rules: the
 * first matching rule wins (design note 8). Put specific rules such as "mock-claude-*"
 * before general ones such as "mock-*".
 *
 * <p>A rule names one provider, or an ordered failover list (design note 25). Every rule is
 * resolved and checked at startup, so a broken config fails before the first request.
 */
@Component
public class ModelRouter {

    private final Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    /** Rule pattern -> its targets, in rule order. */
    private final Map<String, List<ProviderTarget>> rules = new LinkedHashMap<>();

    public ModelRouter(GatewayProperties properties) {
        properties.providers().forEach((name, p) ->
                providers.put(name, new ProviderConfig(name, p.type(), stripTrailingSlash(p.baseUrl()), p.apiKey())));
        for (Route route : properties.routes()) {
            rules.put(route.modelPattern(), resolve(route));
        }
    }

    /** The providers to try for {@code model}, in order. Never empty. */
    public List<ProviderTarget> targets(String model) {
        for (Map.Entry<String, List<ProviderTarget>> rule : rules.entrySet()) {
            if (matches(rule.getKey(), model)) {
                return rule.getValue();
            }
        }
        throw new UnknownModelException(model);
    }

    /** The first provider for {@code model}. */
    public ProviderConfig route(String model) {
        return targets(model).get(0).provider();
    }

    /** All configured provider instances (used for startup checks). */
    public Iterable<ProviderConfig> providers() {
        return providers.values();
    }

    private List<ProviderTarget> resolve(Route route) {
        boolean single = route.provider() != null && !route.provider().isBlank();
        boolean list = route.providers() != null && !route.providers().isEmpty();
        if (single == list) {
            throw new IllegalStateException("Route '" + route.modelPattern()
                    + "' must set exactly one of 'provider' or 'providers'");
        }
        List<Target> entries = single ? List.of(new Target(route.provider(), null)) : route.providers();
        List<ProviderTarget> targets = new ArrayList<>();
        for (Target entry : entries) {
            ProviderConfig provider = providers.get(entry.name());
            if (provider == null) {
                throw new IllegalStateException("Route '" + route.modelPattern()
                        + "' refers to unknown provider '" + entry.name() + "'");
            }
            targets.add(new ProviderTarget(provider, entry.model()));
        }
        return List.copyOf(targets);
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
