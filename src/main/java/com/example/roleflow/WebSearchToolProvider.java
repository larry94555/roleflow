package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code web_search} tool — the key tool for resolving requests for information. It searches the web
 * through DuckDuckGo's no-API-key endpoints: the full {@code html.duckduckgo.com} page first, then the
 * lighter {@code lite.duckduckgo.com} page as a fallback. Results are returned as a JSON document
 * ({@code query}, {@code provider}, {@code results[]} of {rank,title,url,snippet}, and provider notes).
 *
 * <p>The network call is behind an injectable {@link HtmlFetcher} so the parsing and orchestration can be
 * unit-tested offline. Modeled on goalmaker's web search provider, simplified for a first tool.
 */
@Component
public class WebSearchToolProvider implements ToolProvider {

    /** Fetches the HTML at a URL. Swappable so tests can return canned pages without a network. */
    @FunctionalInterface
    public interface HtmlFetcher {
        String fetch(String url) throws Exception;
    }

    private final ObjectMapper mapper;
    private final String htmlUrl;
    private final String liteUrl;
    private final int defaultMaxResults;
    private final HtmlFetcher fetcher;

    @Autowired
    public WebSearchToolProvider(
            ObjectMapper mapper,
            @Value("${web.search.duckduckgo-url:https://html.duckduckgo.com/html/}") String htmlUrl,
            @Value("${web.search.duckduckgo-lite-url:https://lite.duckduckgo.com/lite/}") String liteUrl,
            @Value("${web.search.max-results:5}") int defaultMaxResults,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(mapper, htmlUrl, liteUrl, defaultMaxResults, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    WebSearchToolProvider(ObjectMapper mapper, String htmlUrl, String liteUrl, int defaultMaxResults,
                          HtmlFetcher fetcher) {
        this.mapper = mapper;
        this.htmlUrl = htmlUrl;
        this.liteUrl = liteUrl;
        this.defaultMaxResults = defaultMaxResults;
        this.fetcher = fetcher;
    }

    @Override
    public List<Tool> tools() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "The search query."));
        properties.put("max_results", Map.of("type", "integer",
                "description", "Maximum number of results (1-20).", "minimum", 1, "maximum", 20));
        properties.put("time_range", Map.of("type", "string",
                "description", "Optional recency filter.", "enum", List.of("day", "week", "month", "year")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return List.of(new Tool(
                "web_search",
                "Search the web via DuckDuckGo (HTML with a Lite fallback) and return relevance-ordered, "
                        + "sourced results as JSON. Use this to resolve requests for information.",
                schema,
                "builtin:web_search",
                this::execute));
    }

    /** The tool executor: runs the search and serializes the payload to JSON. */
    String execute(Map<String, Object> arguments) throws Exception {
        return mapper.writeValueAsString(searchPayload(arguments));
    }

    /** Runs the search and returns the structured payload (separated for testing). */
    Map<String, Object> searchPayload(Map<String, Object> arguments) {
        String query = string(arguments.get("query")).trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        int maxResults = clamp(arguments.get("max_results"), defaultMaxResults, 1, 20);
        String timeRange = string(arguments.get("time_range")).trim().toLowerCase(Locale.ROOT);

        List<String> notes = new ArrayList<>();
        List<WebSearchResult> results = List.of();
        String selectedProvider = "none";

        for (Provider provider : providers()) {
            try {
                String url = DuckDuckGoSearch.buildUrl(provider.endpoint(), query, timeRange);
                String html = fetcher.fetch(url);
                List<WebSearchResult> parsed = provider.lite()
                        ? DuckDuckGoSearch.parseLite(html, url, maxResults)
                        : DuckDuckGoSearch.parseHtml(html, url, maxResults);
                if (!parsed.isEmpty()) {
                    results = parsed;
                    selectedProvider = provider.name();
                    break;
                }
                notes.add(provider.name() + ": no results");
            } catch (Exception failure) {
                notes.add(provider.name() + ": " + message(failure));
            }
        }
        return payload(query, selectedProvider, results, notes);
    }

    private List<Provider> providers() {
        List<Provider> providers = new ArrayList<>();
        if (htmlUrl != null && !htmlUrl.isBlank()) providers.add(new Provider("duckduckgo-html", htmlUrl, false));
        if (liteUrl != null && !liteUrl.isBlank()) providers.add(new Provider("duckduckgo-lite", liteUrl, true));
        return providers;
    }

    private static Map<String, Object> payload(String query, String provider, List<WebSearchResult> results,
                                               List<String> notes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("provider", provider);
        payload.put("retrieved_at", Instant.now().toString());
        payload.put("result_count", results.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            WebSearchResult result = results.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            item.put("title", result.title());
            item.put("url", result.url());
            item.put("snippet", result.snippet());
            items.add(item);
        }
        payload.put("results", items);
        if (!notes.isEmpty()) payload.put("notes", notes);
        return payload;
    }

    private static HtmlFetcher defaultFetcher(int timeoutSeconds, int maxResponseBytes) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        int limit = Math.max(1, maxResponseBytes);
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) roleflow/0.1 web_search")
                    .header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            try (InputStream stream = response.body()) {
                return new String(stream.readNBytes(limit), StandardCharsets.UTF_8);
            }
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int clamp(Object value, int fallback, int minimum, int maximum) {
        int parsed;
        try {
            parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException invalid) {
            parsed = fallback;
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Provider(String name, String endpoint, boolean lite) {}
}
