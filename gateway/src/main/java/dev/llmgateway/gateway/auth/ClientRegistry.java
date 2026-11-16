package dev.llmgateway.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Finds the client that owns a presented API key.
 *
 * <p>The presented key is hashed and the hash is looked up in a map. Because the
 * lookup compares hashes, not keys, response timing cannot reveal how many leading
 * characters of a guess were right (the classic timing attack on naive string
 * comparison).
 */
@Component
public class ClientRegistry {

    private final Map<String, ClientIdentity> byKeyHash = new HashMap<>();

    public ClientRegistry(AuthProperties properties) {
        Set<String> ids = new HashSet<>();
        for (AuthProperties.Client client : properties.clients()) {
            String hash = client.keySha256().toLowerCase(Locale.ROOT);
            if (!ids.add(client.id())) {
                throw new IllegalStateException("Duplicate client id '" + client.id() + "'");
            }
            if (byKeyHash.containsKey(hash)) {
                throw new IllegalStateException("Clients '" + byKeyHash.get(hash).id() + "' and '"
                        + client.id() + "' share the same API key hash");
            }
            byKeyHash.put(hash, new ClientIdentity(client.id(), client.requestsPerMinute(), client.tokensPerMinute()));
        }
    }

    public Optional<ClientIdentity> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKeyHash.get(sha256Hex(presentedKey)));
    }

    public int size() {
        return byKeyHash.size();
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
