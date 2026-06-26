package com.example.roleflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The internal tool registry. It collects the tools contributed by every {@link ToolProvider} bean and
 * makes them discoverable and callable using the Model Context Protocol (MCP) vocabulary:
 * {@link #listForMcp()} backs {@code tools/list}, and {@link #call} backs {@code tools/call}.
 *
 * <p>Registration is automatic: Spring injects all {@code ToolProvider} beans, so adding a new tool only
 * requires adding a provider component. Tool names are unique; a duplicate name is ignored with a warning.
 */
@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<ToolProvider> providers) {
        for (ToolProvider provider : providers) {
            for (Tool tool : provider.tools()) {
                register(tool);
            }
        }
        log.info("[tools] registered {} tool(s): {}", tools.size(), tools.keySet());
    }

    private void register(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            log.warn("[tools] duplicate tool name ignored: {}", tool.name());
        }
    }

    /** All registered tool names, in registration order. */
    public synchronized List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    public synchronized boolean isEmpty() {
        return tools.isEmpty();
    }

    /** The MCP {@code tools/list} payload: one {@code {name, description, inputSchema}} per tool. */
    public synchronized List<Map<String, Object>> listForMcp() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool tool : tools.values()) {
            list.add(tool.toMcp());
        }
        return list;
    }

    /**
     * Invokes a tool by name (the MCP {@code tools/call} operation) and returns its text result.
     *
     * @throws IllegalArgumentException if no tool with that name is registered
     * @throws Exception                if the tool itself fails
     */
    public String call(String name, Map<String, Object> arguments) throws Exception {
        Tool tool;
        synchronized (this) {
            tool = tools.get(name);
        }
        if (tool == null) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        log.info("[tools] calling {} source={} arguments={}", name, tool.source(), arguments);
        String result = tool.executor().execute(arguments == null ? Map.of() : arguments);
        return result == null || result.isBlank() ? "(tool completed with no output)" : result;
    }
}
