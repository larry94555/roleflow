# Tool_Registry.md

RoleFlow has an **internal tool registry** that lets the application discover and call tools using the
**Model Context Protocol (MCP)** vocabulary. The first tool is **`web_search`** — the key tool for
resolving requests for information.

This document explains how to **add**, **register**, **discover**, and **use** tools.

---

## Concepts

| Piece | What it is |
|-------|------------|
| [`Tool`](src/main/java/com/example/roleflow/Tool.java) | One tool: `name`, `description`, `inputSchema` (JSON Schema), `source`, and an `executor`. |
| [`ToolProvider`](src/main/java/com/example/roleflow/ToolProvider.java) | A bean that contributes one or more `Tool`s (`List<Tool> tools()`). |
| [`ToolRegistry`](src/main/java/com/example/roleflow/ToolRegistry.java) | Collects every `ToolProvider` bean and exposes discovery + invocation. |
| [`McpController`](src/main/java/com/example/roleflow/McpController.java) | The internal MCP server (JSON-RPC 2.0 at `POST /mcp`) plus `GET /mcp/tools`. |

The tool shape follows MCP: `tools/list` returns `{ name, description, inputSchema }` per tool, and
`tools/call` runs a tool by `name` with `arguments` and returns a `content` array.

---

## Adding a tool

A tool is any class that produces one or more `Tool` objects. Implement `ToolProvider` and annotate it
with `@Component` — that is the entire installation step.

```java
package com.example.roleflow;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class TimeToolProvider implements ToolProvider {

    @Override
    public List<Tool> tools() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("zone", Map.of("type", "string",
                        "description", "IANA time zone, e.g. America/New_York")),
                "required", List.of());            // no required arguments

        return List.of(new Tool(
                "current_time",                    // name (must be unique)
                "Return the current time in an optional time zone.",
                schema,                            // inputSchema (JSON Schema)
                "builtin:current_time",            // source (for provenance/logging)
                args -> {                          // executor: Map<String,Object> -> String
                    String zone = String.valueOf(args.getOrDefault("zone", "UTC"));
                    return java.time.ZonedDateTime.now(java.time.ZoneId.of(zone)).toString();
                }));
    }
}
```

Guidelines:
- **`name`** is unique across all providers. A duplicate name is ignored with a warning (first wins).
- **`inputSchema`** is a JSON Schema object (`type: object`, `properties`, optional `required`). It is
  what clients see during discovery, so describe each argument.
- **`executor`** receives the parsed `arguments` map and returns a `String` (often JSON). Throwing an
  exception is fine — it is surfaced to the caller as a tool error.
- A provider may return **several** tools, and the application may have **many** providers.

A tool can also wrap an external MCP server, a script, or any API — the registry only cares that the
provider returns `Tool`s.

---

## Registering tools

Registration is **automatic**. `ToolRegistry` receives every `ToolProvider` bean via Spring constructor
injection and registers their tools at startup:

```
[tools] registered 2 tool(s): [web_search, current_time]
```

There is nothing else to wire — adding the `@Component` from the previous section is enough. (Internally,
tools are stored by name; `ToolRegistry` is the single place that lists and calls them.)

---

## Discovering tools

Discovery uses the MCP `tools/list` method on the internal MCP server.

**JSON-RPC (the MCP way):**

```bash
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "web_search",
        "description": "Search the web via DuckDuckGo ...",
        "inputSchema": {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "The search query." },
            "max_results": { "type": "integer", "minimum": 1, "maximum": 20 },
            "time_range": { "type": "string", "enum": ["day","week","month","year"] }
          },
          "required": ["query"]
        }
      }
    ]
  }
}
```

**Quick convenience (browser/curl):**

```bash
curl -s http://localhost:8080/mcp/tools
```

returns the same `{ "tools": [ … ] }` list.

The MCP handshake method `initialize` is also supported and advertises the `tools` capability:

```bash
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize"}'
```

In code, the same discovery is available directly from the registry:

```java
List<Map<String, Object>> tools = toolRegistry.listForMcp();
boolean hasSearch = toolRegistry.contains("web_search");
```

---

## Using a tool

Invoke a tool with the MCP `tools/call` method: pass the tool `name` and an `arguments` object matching
its `inputSchema`.

```bash
curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"web_search","arguments":{"query":"model context protocol spec","max_results":3}}}'
```

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      { "type": "text", "text": "{\"query\":\"model context protocol spec\",\"provider\":\"duckduckgo-html\",\"result_count\":3,\"results\":[ … ]}" }
    ],
    "isError": false
  }
}
```

- The tool's text result is returned in the `content` array (MCP shape). For `web_search` the text is a
  JSON document with `query`, `provider`, `retrieved_at`, `result_count`, and `results[]`
  (`rank`, `title`, `url`, `snippet`), plus `notes` describing any provider that was skipped or failed.
- If the tool fails (or the name is unknown), the result has `"isError": true` and the `content` text
  explains the problem — the JSON-RPC call itself still succeeds.

In code:

```java
String json = toolRegistry.call("web_search", Map.of("query", "legendre conjecture", "max_results", 5));
```

---

## The `web_search` tool

[`WebSearchToolProvider`](src/main/java/com/example/roleflow/WebSearchToolProvider.java) searches the web
through DuckDuckGo's no-API-key endpoints:

1. the full `html.duckduckgo.com` page, then
2. the lighter `lite.duckduckgo.com` page as a fallback.

Results are de-duplicated by the page order and returned as JSON. The HTTP call is behind an injectable
`HtmlFetcher`, so the parsing is unit-tested offline with canned HTML.

**Arguments:** `query` (required), `max_results` (1–20, default 5), `time_range`
(`day`/`week`/`month`/`year`, optional).

**Configuration** (in `application.properties`):

| Property | Default | Description |
|----------|---------|-------------|
| `web.search.duckduckgo-url` | `https://html.duckduckgo.com/html/` | Primary endpoint. |
| `web.search.duckduckgo-lite-url` | `https://lite.duckduckgo.com/lite/` | Fallback endpoint. |
| `web.search.max-results` | `5` | Default result count. |
| `web.search.timeout-seconds` | `20` | Per-request timeout. |
| `web.search.max-response-bytes` | `1048576` | Response size cap. |

> Note: `web_search` makes outbound HTTP requests to DuckDuckGo when actually invoked. Discovery
> (`tools/list`) does not; only `tools/call` performs the search.

---

## Summary

- **Add** a tool → write a `@Component implements ToolProvider` returning `Tool`s.
- **Register** → automatic; the `ToolRegistry` picks up every provider bean at startup.
- **Discover** → `POST /mcp` `tools/list` (or `GET /mcp/tools`, or `ToolRegistry.listForMcp()`).
- **Use** → `POST /mcp` `tools/call` with `{name, arguments}` (or `ToolRegistry.call(name, args)`).
