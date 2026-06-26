package com.example.roleflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The structured reply the model returns for each role: text to show the user ({@link #message}), the
 * {@link #decision} that drives the transition, and an optional {@link #artifact} (file content). Parsing
 * is tolerant — the model may wrap the JSON in extra prose, missing fields default to empty, and literal
 * (unescaped) newlines inside string values are accepted, since smaller models frequently emit them.
 */
public record RoleFlowReply(String message, String decision, String artifact) {

    public static RoleFlowReply parse(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return new RoleFlowReply("", "", "");
        }
        String json = extractJsonObject(raw);
        if (json != null) {
            try {
                // Allow raw newlines/tabs inside JSON strings — models often format the artifact/message
                // with real line breaks rather than \n, which strict JSON would reject.
                JsonNode node = mapper.reader()
                        .with(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS)
                        .readTree(json);
                if (node != null && node.isObject()) {
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
