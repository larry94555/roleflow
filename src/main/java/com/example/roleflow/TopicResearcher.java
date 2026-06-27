package com.example.roleflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fetches general web context about a topic by invoking the {@code web_search} tool from the
 * {@link ToolRegistry}. The result is a short, human-readable list of sourced snippets that a reviewing
 * role can use to sanity-check a plan against how the topic actually works (e.g. that finding a
 * counterexample to a mathematical conjecture is a complete result, not a defect to fix).
 *
 * <p>It is best-effort: if the tool is unavailable or the search fails, a short note is returned instead
 * so the reviewing role still runs.
 */
@Component
public class TopicResearcher {
    private static final Logger log = LoggerFactory.getLogger(TopicResearcher.class);

    private static final int MAX_QUERY_LENGTH = 256;

    private final ToolRegistry tools;
    private final ObjectMapper mapper;
    private final int maxResults;

    public TopicResearcher(ToolRegistry tools, ObjectMapper mapper,
                           @Value("${roleflow.research.max-results:3}") int maxResults) {
        this.tools = tools;
        this.mapper = mapper;
        this.maxResults = Math.max(1, maxResults);
    }

    /** Returns a formatted list of web snippets about {@code topic}, or "" / a short note if unavailable. */
    public String context(String topic) {
        if (topic == null || topic.isBlank() || tools == null || !tools.contains("web_search")) {
            return "";
        }
        String query = topic.strip();
        if (query.length() > MAX_QUERY_LENGTH) query = query.substring(0, MAX_QUERY_LENGTH);
        try {
            String json = tools.call("web_search", Map.of("query", query, "max_results", maxResults));
            JsonNode root = mapper.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                // Surface the provider's notes (e.g. "duckduckgo-html: HTTP 202") so an empty result is
                // diagnosable in the audit trail rather than a silent blank.
                JsonNode notes = root.path("notes");
                if (notes.isArray() && !notes.isEmpty()) {
                    StringBuilder reason = new StringBuilder("(No web results were found for the topic. ");
                    for (int i = 0; i < notes.size(); i++) {
                        if (i > 0) reason.append("; ");
                        reason.append(notes.get(i).asText(""));
                    }
                    return reason.append(")").toString();
                }
                return "(No web results were found for the topic.)";
            }
            StringBuilder context = new StringBuilder();
            for (JsonNode result : results) {
                String title = result.path("title").asText("").strip();
                String snippet = result.path("snippet").asText("").strip();
                if (title.isBlank() && snippet.isBlank()) continue;
                context.append("- ").append(title);
                if (!snippet.isBlank()) context.append(": ").append(snippet);
                context.append('\n');
            }
            return context.toString().strip();
        } catch (Exception failure) {
            log.warn("[research] web context unavailable: {}", failure.getMessage());
            return "(Web context was unavailable: " + failure.getMessage() + ")";
        }
    }
}
