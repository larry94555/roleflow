package com.example.roleflow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal Model Context Protocol (MCP) server over JSON-RPC 2.0. It exposes the {@link ToolRegistry} so
 * tools can be <b>discovered</b> ({@code tools/list}) and <b>used</b> ({@code tools/call}) by any internal
 * client. A small {@code GET /mcp/tools} convenience returns the same tool list for quick inspection.
 *
 * <p>Example discovery request:
 * <pre>{@code POST /mcp  {"jsonrpc":"2.0","id":1,"method":"tools/list"} }</pre>
 * Example use request:
 * <pre>{@code POST /mcp  {"jsonrpc":"2.0","id":2,"method":"tools/call",
 *                         "params":{"name":"web_search","arguments":{"query":"mcp spec"}}} }</pre>
 */
@RestController
public class McpController {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ToolRegistry registry;

    public McpController(ToolRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/mcp")
    public Map<String, Object> rpc(@RequestBody JsonRpcRequest request) {
        try {
            return switch (request.method() == null ? "" : request.method()) {
                case "initialize" -> success(request.id(), initializeResult());
                case "tools/list" -> success(request.id(), Map.of("tools", registry.listForMcp()));
                case "tools/call" -> success(request.id(), callResult(request.params()));
                case "ping" -> success(request.id(), Map.of());
                default -> error(request.id(), -32601, "Method not found: " + request.method());
            };
        } catch (IllegalArgumentException invalid) {
            return error(request.id(), -32602, invalid.getMessage());
        } catch (Exception unexpected) {
            return error(request.id(), -32603, "Internal error: " + unexpected.getMessage());
        }
    }

    /** Convenience: the same list as {@code tools/list}, for a quick GET in a browser or curl. */
    @GetMapping("/mcp/tools")
    public Map<String, Object> tools() {
        return Map.of("tools", registry.listForMcp());
    }

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "roleflow", "version", "0.0.1"));
    }

    /** Runs a tool and returns an MCP {@code tools/call} result. Tool failures become {@code isError} results. */
    private Map<String, Object> callResult(Map<String, Object> params) {
        if (params == null || !(params.get("name") instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("tools/call requires a 'name'");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        try {
            String text = registry.call(name, arguments);
            return Map.of("content", List.of(textContent(text)), "isError", false);
        } catch (Exception toolFailure) {
            return Map.of("content", List.of(textContent("ERROR: " + toolFailure.getMessage())),
                    "isError", true);
        }
    }

    private static Map<String, Object> textContent(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> success(Object id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id == null ? "" : id, "result", result);
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", id == null ? "" : id,
                "error", Map.of("code", code, "message", message == null ? "" : message));
    }

    /** A JSON-RPC 2.0 request. {@code params} is optional. */
    public record JsonRpcRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {}
}
