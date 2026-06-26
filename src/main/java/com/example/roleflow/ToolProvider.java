package com.example.roleflow;

import java.util.List;

/**
 * Supplies one or more {@link Tool}s to the {@link ToolRegistry}. Every Spring bean that implements this
 * interface is registered automatically at startup, so installing a new tool is simply a matter of adding
 * a {@code @Component} that implements {@code ToolProvider}. See {@code Tool_Registry.md}.
 */
public interface ToolProvider {

    /** The tools this provider contributes. Called once when the registry is built. */
    List<Tool> tools();
}
