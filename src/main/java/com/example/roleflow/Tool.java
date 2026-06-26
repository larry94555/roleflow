package com.example.roleflow;

import java.util.Map;

/**
 * A single tool that can be discovered and invoked through the {@link ToolRegistry}.
 *
 * <p>The shape mirrors the Model Context Protocol (MCP) tool definition: a {@code name}, a human-readable
 * {@code description}, and an {@code inputSchema} (a JSON Schema object describing the arguments). The
 * {@code source} records where the tool came from (e.g. {@code "builtin:web_search"}) for provenance, and
 * {@code executor} performs the work.
 */
public record Tool(String name, String description, Map<String, Object> inputSchema, String source,
                   Executor executor) {

    /** Performs the tool's work given its arguments and returns a text result. */
    @FunctionalInterface
    public interface Executor {
        String execute(Map<String, Object> arguments) throws Exception;
    }

    /** The MCP {@code tools/list} representation of this tool. */
    public Map<String, Object> toMcp() {
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", inputSchema);
    }
}
