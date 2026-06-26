package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDuckGoSearchTest {

    private static final String HTML = """
            <div class="result results_links web-result">
              <div class="links_main">
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fmcp&rut=x">MCP spec</a>
                <a class="result__snippet">The Model Context Protocol standard.</a>
              </div>
            </div>
            <div class="result results_links web-result">
              <div class="links_main">
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Ftools">Tools</a>
                <a class="result__snippet">Tool registries explained.</a>
              </div>
            </div>
            """;

    private static final String LITE = """
            <table>
              <tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Flite.example%2Fa">Lite A</a></td></tr>
              <tr><td class="result-snippet">First lite snippet.</td></tr>
              <tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Flite.example%2Fb">Lite B</a></td></tr>
              <tr><td class="result-snippet">Second lite snippet.</td></tr>
            </table>
            """;

    @Test
    void parsesHtmlResultsAndDecodesRedirects() {
        List<WebSearchResult> results = DuckDuckGoSearch.parseHtml(HTML, "https://html.duckduckgo.com/", 10);

        assertEquals(2, results.size());
        assertEquals("MCP spec", results.get(0).title());
        assertEquals("https://example.com/mcp", results.get(0).url());
        assertEquals("The Model Context Protocol standard.", results.get(0).snippet());
        assertEquals("https://example.org/tools", results.get(1).url());
    }

    @Test
    void respectsTheResultLimit() {
        assertEquals(1, DuckDuckGoSearch.parseHtml(HTML, "https://x/", 1).size());
    }

    @Test
    void parsesLiteResults() {
        List<WebSearchResult> results = DuckDuckGoSearch.parseLite(LITE, "https://lite.duckduckgo.com/", 10);

        assertEquals(2, results.size());
        assertEquals("Lite A", results.get(0).title());
        assertEquals("https://lite.example/a", results.get(0).url());
        assertEquals("First lite snippet.", results.get(0).snippet());
    }

    @Test
    void decodeRedirectHandlesWrappedAndPlainLinks() {
        assertEquals("https://example.com/x",
                DuckDuckGoSearch.decodeRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fx&rut=y"));
        assertEquals("https://plain.example", DuckDuckGoSearch.decodeRedirect("https://plain.example"));
        assertEquals("https://protocol-relative.example",
                DuckDuckGoSearch.decodeRedirect("//protocol-relative.example"));
        assertEquals("", DuckDuckGoSearch.decodeRedirect(""));
    }

    @Test
    void buildUrlEncodesQueryAndAddsTimeFilter() {
        assertEquals("https://html.duckduckgo.com/?q=mcp+spec",
                DuckDuckGoSearch.buildUrl("https://html.duckduckgo.com/", "mcp spec", ""));
        assertTrue(DuckDuckGoSearch.buildUrl("https://x/", "a", "week").endsWith("&df=w"));
    }

    @Test
    void timeCodeMapsKnownRangesOnly() {
        assertEquals("d", DuckDuckGoSearch.timeCode("day"));
        assertEquals("y", DuckDuckGoSearch.timeCode("YEAR"));
        assertEquals("", DuckDuckGoSearch.timeCode("decade"));
        assertEquals("", DuckDuckGoSearch.timeCode(null));
    }
}
