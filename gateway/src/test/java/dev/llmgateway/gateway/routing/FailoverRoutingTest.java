package dev.llmgateway.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import dev.llmgateway.gateway.config.GatewayProperties;
import dev.llmgateway.gateway.config.GatewayProperties.Provider;
import dev.llmgateway.gateway.config.GatewayProperties.Route;
import dev.llmgateway.gateway.config.GatewayProperties.Target;
import org.junit.jupiter.api.Test;

/** Routes with an ordered list of providers (design note 25). */
class FailoverRoutingTest {

    private static final Map<String, Provider> PROVIDERS = Map.of(
            "openai", new Provider("openai", "https://api.openai.com", "k1"),
            "backup", new Provider("openai", "http://backup", "k2"),
            "anthropic", new Provider("anthropic", "https://api.anthropic.com", "k3"));

    @Test
    void targetsComeBackInConfiguredOrder() {
        ModelRouter router = router(new Route("gpt-*", null, List.of(
                new Target("openai", null), new Target("backup", null), new Target("anthropic", "claude-x"))));

        List<ProviderTarget> targets = router.targets("gpt-4o");

        assertThat(targets).extracting(t -> t.provider().name()).containsExactly("openai", "backup", "anthropic");
        assertThat(router.route("gpt-4o").name()).isEqualTo("openai"); // route() is the first target
    }

    @Test
    void modelOverrideAppliesOnlyWhereSet() {
        List<ProviderTarget> targets = router(new Route("gpt-*", null, List.of(
                new Target("openai", null), new Target("anthropic", "claude-x")))).targets("gpt-4o");

        assertThat(targets.get(0).upstreamModel("gpt-4o")).isEqualTo("gpt-4o");
        assertThat(targets.get(1).upstreamModel("gpt-4o")).isEqualTo("claude-x");
    }

    @Test
    void singleProviderFormIsAListOfOne() {
        List<ProviderTarget> targets = router(Route.single("gpt-*", "openai")).targets("gpt-4o");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).modelOverride()).isNull();
    }

    @Test
    void unknownProviderInAListFailsAtStartup() {
        assertThatThrownBy(() -> router(new Route("gpt-*", null, List.of(
                new Target("openai", null), new Target("nope", null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nope");
    }

    @Test
    void routeMustSetExactlyOneForm() {
        assertThatThrownBy(() -> router(new Route("gpt-*", "openai", List.of(new Target("backup", null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> router(new Route("gpt-*", null, List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one");
    }

    private static ModelRouter router(Route route) {
        return new ModelRouter(new GatewayProperties(PROVIDERS, List.of(route), null));
    }
}
