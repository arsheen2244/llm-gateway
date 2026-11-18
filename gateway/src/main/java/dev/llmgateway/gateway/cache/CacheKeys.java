package dev.llmgateway.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;

/**
 * How requests are turned into cache keys (design note 32).
 *
 * <p><b>Scope</b>: who asked, which model, which system prompt, which sampling settings. Two
 * requests can only ever share an answer if their scope is identical, so one client never
 * receives another client's answer, and an answer generated under one system prompt or
 * temperature is never served under another.
 *
 * <p><b>Normalization</b> (for both the exact key and the text that gets embedded): trim,
 * collapse every run of whitespace to one space, and Unicode NFC (so "é" typed as one
 * character or as "e" + accent is the same). Case is <b>kept</b>: "US" and "us" can mean
 * different things, and a wrong hit is worse than a miss.
 *
 * <p>Not part of any key: {@code stream} and {@code stream_options}. The same answer can be
 * replayed either way.
 */
public final class CacheKeys {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private CacheKeys() {
    }

    /** The scope as a short stable string (a hash), usable as a Redis key part and search tag. */
    public static String scope(String clientId, ChatCompletionRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append("client=").append(clientId).append('\n')
                .append("model=").append(request.model()).append('\n')
                .append("system=").append(normalize(systemPrompt(request.messages()))).append('\n')
                .append("temperature=").append(request.temperature()).append('\n')
                .append("top_p=").append(request.topP()).append('\n')
                .append("max_tokens=").append(request.maxTokens()).append('\n')
                .append("stop=").append(request.stop());
        return sha256(canonical.toString());
    }

    /** The exact-match key: the scope plus every non-system message, normalized, in order. */
    public static String exact(String clientId, ChatCompletionRequest request) {
        StringBuilder canonical = new StringBuilder(scope(clientId, request));
        for (ChatMessage message : request.messages()) {
            if (!message.role().equals("system")) {
                // Length-prefixed, so ("ab","c") and ("a","bc") can't produce the same text.
                String content = normalize(message.content());
                canonical.append('\n').append(message.role()).append(':')
                        .append(content.length()).append(':').append(content);
            }
        }
        return sha256(canonical.toString());
    }

    /** The text that is embedded for the semantic layer: the (single) user message, normalized. */
    public static String semanticText(ChatCompletionRequest request) {
        for (ChatMessage message : request.messages()) {
            if (message.role().equals("user")) {
                return normalize(message.content());
            }
        }
        return "";
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC);
        return WHITESPACE.matcher(composed.strip()).replaceAll(" ");
    }

    private static String systemPrompt(List<ChatMessage> messages) {
        StringBuilder system = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.role().equals("system")) {
                system.append(message.content()).append('\n');
            }
        }
        return system.toString();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
