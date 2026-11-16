package dev.llmgateway.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;

import dev.llmgateway.gateway.support.TestKeys;
import org.junit.jupiter.api.Test;

class ClientRegistryTest {

    private static AuthProperties.Client client(String id, String key) {
        return new AuthProperties.Client(id, TestKeys.sha256(key), 10, 1000);
    }

    @Test
    void validKeyFindsItsClientWithLimits() {
        ClientRegistry registry = new ClientRegistry(new AuthProperties(List.of(client("team-a", "secret-a"))));

        assertThat(registry.authenticate("secret-a"))
                .hasValue(new ClientIdentity("team-a", 10, 1000));
    }

    @Test
    void unknownBlankOrMissingKeyFindsNothing() {
        ClientRegistry registry = new ClientRegistry(new AuthProperties(List.of(client("team-a", "secret-a"))));

        assertThat(registry.authenticate("secret-b")).isEmpty();
        assertThat(registry.authenticate("")).isEmpty();
        assertThat(registry.authenticate(null)).isEmpty();
    }

    @Test
    void configuredHashMayBeUpperCase() {
        AuthProperties.Client upper = new AuthProperties.Client(
                "team-a", TestKeys.sha256("secret-a").toUpperCase(Locale.ROOT), 10, 1000);

        assertThat(new ClientRegistry(new AuthProperties(List.of(upper))).authenticate("secret-a")).isPresent();
    }

    @Test
    void noClientsMeansNothingAuthenticates() {
        ClientRegistry registry = new ClientRegistry(new AuthProperties(List.of()));

        assertThat(registry.size()).isZero();
        assertThat(registry.authenticate("anything")).isEmpty();
    }

    @Test
    void duplicateIdsOrKeysFailAtStartup() {
        assertThatThrownBy(() -> new ClientRegistry(new AuthProperties(List.of(
                client("team-a", "k1"), client("team-a", "k2")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate client id");
        assertThatThrownBy(() -> new ClientRegistry(new AuthProperties(List.of(
                client("team-a", "same"), client("team-b", "same")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("same API key hash");
    }

    @Test
    void hashMatchesStandardSha256() {
        // Known value: SHA-256("abc")
        assertThat(ClientRegistry.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
