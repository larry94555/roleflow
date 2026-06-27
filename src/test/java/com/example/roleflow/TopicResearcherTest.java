package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicResearcherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private TopicResearcher researcher(Tool.Executor webSearch) {
        ToolRegistry tools = new ToolRegistry(List.of(() -> List.of(
                new Tool("web_search", "search", Map.of("type", "object"), "test:web_search", webSearch))));
        return new TopicResearcher(tools, mapper, 3);
    }

    @Test
    void formatsSearchResultsAsContextLines() {
        String json = "{\"results\":[{\"title\":\"Legendre's conjecture\",\"snippet\":\"a prime exists "
                + "between consecutive squares\"},{\"title\":\"Counterexample\",\"snippet\":\"disproves a "
                + "claim\"}]}";

        String context = researcher(args -> json).context("Legendre's conjecture");

        assertTrue(context.contains("- Legendre's conjecture: a prime exists between consecutive squares"));
        assertTrue(context.contains("- Counterexample: disproves a claim"));
    }

    @Test
    void passesTheTopicAsTheSearchQuery() {
        String[] seen = new String[1];
        TopicResearcher researcher = researcher(args -> {
            seen[0] = String.valueOf(args.get("query"));
            return "{\"results\":[{\"title\":\"t\",\"snippet\":\"s\"}]}";
        });

        researcher.context("how do prime gaps work");

        assertEquals("how do prime gaps work", seen[0]);
    }

    @Test
    void returnsANoteWhenThereAreNoResults() {
        assertTrue(researcher(args -> "{\"results\":[]}").context("obscure topic").contains("No web results"));
    }

    @Test
    void surfacesProviderNotesWhenThereAreNoResults() {
        // When the search returns no results, the provider's notes (e.g. an HTTP status) are included so
        // an empty topic context is diagnosable in the audit trail.
        String json = "{\"results\":[],\"notes\":[\"duckduckgo-html: HTTP 202\",\"duckduckgo-lite: no results\"]}";

        String context = researcher(args -> json).context("mathematics");

        assertTrue(context.contains("No web results"), context);
        assertTrue(context.contains("duckduckgo-html: HTTP 202"), context);
        assertTrue(context.contains("duckduckgo-lite: no results"), context);
    }

    @Test
    void returnsANoteWhenTheSearchFails() {
        String context = researcher(args -> { throw new IllegalStateException("network down"); })
                .context("anything");

        assertTrue(context.contains("unavailable"));
        assertTrue(context.contains("network down"));
    }

    @Test
    void returnsEmptyWhenNoWebSearchToolIsRegistered() {
        ToolRegistry empty = new ToolRegistry(List.of());
        TopicResearcher researcher = new TopicResearcher(empty, mapper, 3);

        assertEquals("", researcher.context("a topic"));
    }

    @Test
    void returnsEmptyForABlankTopic() {
        assertEquals("", researcher(args -> "{\"results\":[]}").context("  "));
    }
}
