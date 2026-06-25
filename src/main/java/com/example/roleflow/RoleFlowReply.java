package com.example.roleflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The structured reply the model returns for each role: text to show the user ({@link #message}), the
 * {@link #decision} that drives the transition, and an optional {@link #artifact} (file content). Parsing
 * is tolerant — the model may wrap the JSON in extra prose, and missing fields default to empty.
 */
public record RoleFlowReply(String message, String decision, String artifact) {

    public static RoleFlowReply parse(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return new RoleFlowReply("", "", "");
        }
        String json = extractJsonObject(raw);
        if (json != null) {
            try {
                JsonNode node = mapper.readTree(json);
                if (node.isObject()) {
                    return new RoleFlowReply(
                            node.path("message").asText(""),
                            node.path("decision").asText(""),
                            node.path("artifact").asText(""));
                }
            } catch (Exception ignored) {
                // Fall through to the plain-text fallback below.
            }
        }
        // No usable JSON: treat the whole reply as the user-facing message with no decision/artifact.
        return new RoleFlowReply(raw.trim(), "", "");
    }

    /** Returns the substring from the first '{' to the matching last '}', or null if none. */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }
}
