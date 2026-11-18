package dev.llmgateway.gateway.cache;

/** The closest cached answer in the client's scope, and how similar its prompt was (0 to 1). */
public record SemanticMatch(CachedAnswer answer, double similarity) {
}
