package dev.llmgateway.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The new part of the message in one chunk. Absent fields are left out of the JSON. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Delta(String role, String content) {
}
