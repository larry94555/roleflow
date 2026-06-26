package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static Tool tool(String name, Tool.Executor executor) {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("text", Map.of("type", "string")));
        return new Tool(name, "desc of " + name, schema, "test:" + name, executor);
    }

    private static ToolProvider provider(Tool... tools) {
        return () -> List.of(tools);
    }

    @Test
    void registersToolsFromAllProviders() {
        ToolRegistry registry = new ToolRegistry(List.of(
                provider(tool("alpha", a -> "A")),
                provider(tool("beta", a -> "B"))));

        assertFalse(registry.isEmpty());
        assertEquals(List.of("alpha", "beta"), registry.names());
        assertTrue(registry.contains("alpha"));
        assertFalse(registry.contains("missing"));
    }

    @Test
    void listForMcpUsesNameDescriptionAndInputSchema() {
        ToolRegistry registry = new ToolRegistry(List.of(provider(tool("echo", a -> "x"))));

        List<Map<String, Object>> list = registry.listForMcp();

        assertEquals(1, list.size());
        Map<String, Object> entry = list.get(0);
        assertEquals("echo", entry.get("name"));
        assertEquals("desc of echo", entry.get("description"));
        assertTrue(entry.containsKey("inputSchema"));
    }

    @Test
    void callInvokesTheToolWithArguments() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(
                provider(tool("echo", args -> "echo:" + args.get("text")))));

        assertEquals("echo:hi", registry.call("echo", Map.of("text", "hi")));
    }

    @Test
    void callReportsPlaceholderForEmptyOutput() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(provider(tool("blank", a -> ""))));

        assertEquals("(tool completed with no output)", registry.call("blank", Map.of()));
    }

    @Test
    void callOnUnknownToolThrows() {
        ToolRegistry registry = new ToolRegistry(List.of(provider(tool("echo", a -> "x"))));

        assertThrows(IllegalArgumentException.class, () -> registry.call("nope", Map.of()));
    }

    @Test
    void duplicateToolNameIsIgnoredKeepingTheFirst() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(
                provider(tool("dup", a -> "first")),
                provider(tool("dup", a -> "second"))));

        assertEquals(List.of("dup"), registry.names());
        assertEquals("first", registry.call("dup", Map.of()));
    }
}
