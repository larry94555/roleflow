package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpControllerTest {

    private McpController controller() {
        Tool echo = new Tool("echo", "Echoes its input",
                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
                "test:echo",
                args -> "echo:" + args.get("text"));
        ToolRegistry registry = new ToolRegistry(List.of(() -> List.of(echo)));
        return new McpController(registry);
    }

    private McpController.JsonRpcRequest request(Object id, String method, Map<String, Object> params) {
        return new McpController.JsonRpcRequest("2.0", id, method, params);
    }

    @Test
    void initializeAdvertisesToolCapability() {
        Map<String, Object> response = controller().rpc(request(1, "initialize", null));

        assertEquals("2.0", response.get("jsonrpc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertTrue(((Map<?, ?>) result.get("capabilities")).containsKey("tools"));
        assertEquals("roleflow", ((Map<?, ?>) result.get("serverInfo")).get("name"));
    }

    @Test
    void toolsListReturnsDiscoverableTools() {
        Map<String, Object> response = controller().rpc(request(2, "tools/list", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<?> tools = (List<?>) result.get("tools");
        assertEquals(1, tools.size());
        Map<?, ?> tool = (Map<?, ?>) tools.get(0);
        assertEquals("echo", tool.get("name"));
        assertTrue(tool.containsKey("inputSchema"));
    }

    @Test
    void toolsCallRunsTheToolAndReturnsContent() {
        Map<String, Object> response = controller().rpc(request(3, "tools/call",
                Map.of("name", "echo", "arguments", Map.of("text", "hi"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertEquals(false, result.get("isError"));
        List<?> content = (List<?>) result.get("content");
        Map<?, ?> first = (Map<?, ?>) content.get(0);
        assertEquals("text", first.get("type"));
        assertEquals("echo:hi", first.get("text"));
    }

    @Test
    void toolsCallOnUnknownToolReturnsIsError() {
        Map<String, Object> response = controller().rpc(request(4, "tools/call",
                Map.of("name", "missing", "arguments", Map.of())));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertEquals(true, result.get("isError"));
        assertTrue(((List<?>) result.get("content")).toString().contains("unknown tool"));
    }

    @Test
    void toolsCallWithoutNameIsAnInvalidParamsError() {
        Map<String, Object> response = controller().rpc(request(5, "tools/call", Map.of()));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(-32602, error.get("code"));
        assertFalse(response.containsKey("result"));
    }

    @Test
    void unknownMethodIsMethodNotFound() {
        Map<String, Object> response = controller().rpc(request(6, "tools/teleport", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(-32601, error.get("code"));
    }

    @Test
    void getToolsConvenienceListsTools() {
        Map<String, Object> tools = controller().tools();

        assertEquals(1, ((List<?>) tools.get("tools")).size());
    }
}
