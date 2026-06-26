package com.example.roleflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolProviderTest {

    private static final String HTML_ONE = """
            <div class="result"><div class="links_main">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fmcp">MCP spec</a>
              <a class="result__snippet">The protocol.</a>
            </div></div>
            """;

    private static final String LITE_ONE = """
            <table>
              <tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Flite.example%2Fa">Lite A</a></td></tr>
              <tr><td class="result-snippet">Lite snippet.</td></tr>
            </table>
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    private WebSearchToolProvider provider(WebSearchToolProvider.HtmlFetcher fetcher) {
        return new WebSearchToolProvider(mapper, "https://html.test/", "https://lite.test/", 5, fetcher);
    }

    @Test
    void exposesAWebSearchToolWithAQueryRequiringSchema() {
        List<Tool> tools = provider(url -> "").tools();

        assertEquals(1, tools.size());
        Tool tool = tools.get(0);
        assertEquals("web_search", tool.name());
        assertEquals("builtin:web_search", tool.source());
        assertEquals(List.of("query"), tool.inputSchema().get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("max_results"));
    }

    @Test
    void searchesViaHtmlProviderFirst() {
        WebSearchToolProvider provider = provider(url -> url.startsWith("https://html.test/") ? HTML_ONE : "");

        Map<String, Object> payload = provider.searchPayload(Map.of("query", "mcp spec"));

        assertEquals("duckduckgo-html", payload.get("provider"));
        assertEquals(1, payload.get("result_count"));
        List<?> results = (List<?>) payload.get("results");
        assertEquals("https://example.com/mcp", ((Map<?, ?>) results.get(0)).get("url"));
    }

    @Test
    void fallsBackToLiteWhenHtmlHasNoResults() {
        WebSearchToolProvider provider = provider(url -> url.startsWith("https://lite.test/") ? LITE_ONE : "<html></html>");

        Map<String, Object> payload = provider.searchPayload(Map.of("query", "anything"));

        assertEquals("duckduckgo-lite", payload.get("provider"));
        assertEquals(1, payload.get("result_count"));
        assertTrue(payload.get("notes").toString().contains("duckduckgo-html: no results"));
    }

    @Test
    void fallsBackToLiteWhenHtmlFetchFails() {
        WebSearchToolProvider provider = provider(url -> {
            if (url.startsWith("https://html.test/")) throw new IllegalStateException("HTTP 503");
            return LITE_ONE;
        });

        Map<String, Object> payload = provider.searchPayload(Map.of("query", "x"));

        assertEquals("duckduckgo-lite", payload.get("provider"));
        assertTrue(payload.get("notes").toString().contains("HTTP 503"));
    }

    @Test
    void reportsNoneWhenEveryProviderFails() {
        Map<String, Object> payload = provider(url -> "<html></html>").searchPayload(Map.of("query", "x"));

        assertEquals("none", payload.get("provider"));
        assertEquals(0, payload.get("result_count"));
    }

    @Test
    void requiresAQuery() {
        WebSearchToolProvider provider = provider(url -> HTML_ONE);

        assertThrows(IllegalArgumentException.class, () -> provider.searchPayload(Map.of("query", "  ")));
        assertThrows(IllegalArgumentException.class, () -> provider.searchPayload(Map.of()));
    }

    @Test
    void executeReturnsJson() throws Exception {
        WebSearchToolProvider provider = provider(url -> HTML_ONE);

        String json = provider.execute(Map.of("query", "mcp"));
        JsonNode node = mapper.readTree(json);

        assertEquals("mcp", node.get("query").asText());
        assertEquals(1, node.get("results").size());
        assertEquals("MCP spec", node.get("results").get(0).get("title").asText());
    }
}
