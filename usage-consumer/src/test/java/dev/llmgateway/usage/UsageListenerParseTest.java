package dev.llmgateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** Reading events: the contract shared with the gateway, and what counts as a bad event. */
@JsonTest
class UsageListenerParseTest {

    @Autowired
    ObjectMapper json;

    private UsageListener listener;
    private ObjectNode contract;

    @BeforeEach
    void setUp() throws Exception {
        listener = new UsageListener(null, json);
        contract = (ObjectNode) json.readTree(Files.readString(Path.of("../contracts/usage-event.json")));
    }

    @Test
    void theContractEventIsReadFieldByField() {
        assertThat(listener.parse(contract.toString())).isEqualTo(new UsageEvent(
                UUID.fromString("7f8c1c8e-3d0a-4c3e-9a51-2b1d6f0c4e11"), "local-dev", "gpt-x", "mock-openai",
                "mock-gpt", 12, 34, 250L, "miss", UsageEvent.Status.SUCCESS, true,
                Instant.parse("2026-11-20T10:15:30.123Z")));
    }

    @Test
    void aFieldAddedLaterIsIgnored() {
        assertThat(listener.parse(contract.put("newField", 1).toString()).clientId()).isEqualTo("local-dev");
    }

    @Test
    void providerAndUpstreamModelMayBeNull() {
        contract.putNull("provider").putNull("upstreamModel");

        assertThat(listener.parse(contract.toString()).provider()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "clientId", "model", "promptTokens", "completionTokens", "latencyMs",
            "cache", "status", "stream", "occurredAt"})
    void anEventMissingARequiredFieldIsInvalid(String field) {
        contract.remove(field);

        assertThatThrownBy(() -> listener.parse(contract.toString())).isInstanceOf(InvalidUsageEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "null", "[]", "{}", "{\"eventId\":\"not-a-uuid\"}"})
    void textThatIsNotAnEventIsInvalid(String value) {
        assertThatThrownBy(() -> listener.parse(value)).isInstanceOf(InvalidUsageEventException.class);
    }

    @Test
    void impossibleValuesAreInvalid() {
        assertThatThrownBy(() -> listener.parse(contract.deepCopy().put("promptTokens", -1).toString()))
                .isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> listener.parse(contract.deepCopy().put("promptTokens", 99_999_999_999L).toString()))
                .isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> listener.parse(contract.deepCopy().put("status", "MAYBE").toString()))
                .isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> listener.parse(null)).isInstanceOf(InvalidUsageEventException.class);
    }
}
