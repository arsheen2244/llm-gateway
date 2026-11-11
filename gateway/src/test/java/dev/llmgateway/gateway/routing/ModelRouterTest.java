package dev.llmgateway.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import dev.llmgateway.gateway.config.GatewayProperties;
import dev.llmgateway.gateway.config.GatewayProperties.Provider;
import dev.llmgateway.gateway.config.GatewayProperties.Route;
import org.junit.jupiter.api.Test;

class ModelRouterTest {

    private static final Map<String, Provider> PROVIDERS = Map.of(
            "openai", new Provider("openai", "https://api.openai.com/", "k1"),
            "mock-anthropic", new Provider("anthropic", "http://mock", "k2"),
            "mock-openai", new Provider("openai", "http://mock", "k3"));

    private final ModelRouter router = new ModelRouter(new GatewayProperties(PROVIDERS, List.of(
            new Route("mock-claude-*", "mock-anthropic"),
            new Route("mock-*", "mock-openai"),
            new Route("gpt-4o", "openai")), null));

    @Test
    void firstMatchingRuleWins() {
        assertThat(router.route("mock-claude-3").name()).isEqualTo("mock-anthropic");
        assertThat(router.route("mock-gpt").name()).isEqualTo("mock-openai");
    }

    @Test
    void exactPatternMatchesOnlyThatName() {
        assertThat(router.route("gpt-4o").name()).isEqualTo("openai");
        assertThatThrownBy(() -> router.route("gpt-4o-mini")).isInstanceOf(UnknownModelException.class);
    }

    @Test
    void trailingSlashInBaseUrlIsRemoved() {
        assertThat(router.route("gpt-4o").baseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    void unknownModelThrows() {
        assertThatThrownBy(() -> router.route("llama-3"))
                .isInstanceOf(UnknownModelException.class)
                .hasMessageContaining("llama-3");
    }

    @Test
    void routeToUndefinedProviderFailsAtStartup() {
        GatewayProperties broken = new GatewayProperties(PROVIDERS, List.of(new Route("x-*", "nope")), null);

        assertThatThrownBy(() -> new ModelRouter(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void apiKeyIsNeverPrinted() {
        assertThat(router.route("gpt-4o").toString()).doesNotContain("k1");
        assertThat(PROVIDERS.get("openai").toString()).doesNotContain("k1");
    }
}
