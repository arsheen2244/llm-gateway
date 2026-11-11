package dev.llmgateway.gateway.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.llmgateway.gateway.routing.ModelRouter;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.springframework.stereotype.Component;

/** Finds the adapter for a provider's type. Spring passes in every ProviderAdapter bean. */
@Component
public class AdapterRegistry {

    private final Map<String, ProviderAdapter> byType;

    public AdapterRegistry(List<ProviderAdapter> adapters, ModelRouter router) {
        this.byType = adapters.stream().collect(Collectors.toMap(ProviderAdapter::type, Function.identity()));
        // Fail at startup if config names a protocol we have no adapter for.
        for (ProviderConfig provider : router.providers()) {
            if (!byType.containsKey(provider.type())) {
                throw new IllegalStateException("Provider '" + provider.name()
                        + "' has unknown type '" + provider.type() + "'. Known types: " + byType.keySet());
            }
        }
    }

    public ProviderAdapter forType(String type) {
        ProviderAdapter adapter = byType.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter for type " + type);
        }
        return adapter;
    }
}
