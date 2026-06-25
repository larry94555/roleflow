package com.example.roleflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Boots the full application context. The test properties disable the managed llama-server and the
 * terminal reader, so this simply confirms every bean wires together.
 */
@SpringBootTest
class RoleFlowApplicationTests {

    @Autowired
    private AskController askController;

    @Autowired
    private LlamaClient llamaClient;

    @Autowired
    private TerminalPromptRunner terminalPromptRunner;

    @Autowired
    private LlamaServerManager llamaServerManager;

    @Test
    void contextLoads() {
        assertNotNull(askController);
        assertNotNull(llamaClient);
        assertNotNull(terminalPromptRunner);
        assertNotNull(llamaServerManager);
    }
}
