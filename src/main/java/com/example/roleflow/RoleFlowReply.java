package com.example.roleflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * The structured reply the model returns for each role: text to show the user ({@link #message}), the
 * {@link #decision} that drives the transition, and an optional {@link #artifact} (file content). Parsing
 * is tolerant — the model may wrap the JSON in extra prose, missing fields default to empty, and literal
 * (unescaped) newlines inside string values are accepted, since smaller models frequently emit them.
 *
 * <p>When a field appears more than once (smaller models sometimes emit a key twice — the real value first,
 * then an empty template echo, e.g. {@code "artifact":"...plan...","artifact":""}), the FIRST occurrence
 * wins, so the meaningful content is never clobbered by a trailing empty duplicate.
 */
public record RoleFlowReply(String message, String decision, String artifact) {

    public static RoleFlowReply parse(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return new RoleFlowReply("", "", "");
        }
        String json = extractJsonObject(raw);
        if (json != null) {
            RoleFlowReply reply = parseObject(json, mapper);
            if (reply != null) {
                return reply;
            }
        }
        // No usable JSON: treat the whole reply as the user-facing message with no decision/artifact.
        return new RoleFlowReply(raw.trim(), "", "");
    }

    /**
     * Streams the top-level fields of the JSON object, keeping the first value seen for each key. Returns
     * {@code null} if the text is not a JSON object so the caller can fall back to plain text.
     */
    private static RoleFlowReply parseObject(String json, ObjectMapper mapper) {
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            // Allow raw newlines/tabs inside JSON strings — models often format the artifact/message with
            // real line breaks rather than \n, which strict JSON would reject.
            parser.enable(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            Map<String, String> fields = new HashMap<>();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    parser.skipChildren(); // ignore a nested value; we only care about the string fields
                } else {
                    fields.putIfAbsent(name, parser.getValueAsString("")); // first occurrence wins
                }
            }
            return new RoleFlowReply(
                    fields.getOrDefault("message", ""),
                    fields.getOrDefault("decision", ""),
                    fields.getOrDefault("artifact", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns the substring from the first '{' to the matching last '}', or null if none. */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }
}
