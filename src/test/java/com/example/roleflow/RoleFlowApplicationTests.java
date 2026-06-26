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

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMemory conversationMemory;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired
    private Summarizer summarizer;

    @Autowired
    private RoleFlowConfig roleFlowConfig;

    @Autowired
    private RoleFlowEngine roleFlowEngine;

    @Autowired
    private RoleFlowSession roleFlowSession;

    @Autowired
    private GoalFileWriter goalFileWriter;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditController auditController;

    @Test
    void contextLoads() {
        assertNotNull(askController);
        assertNotNull(llamaClient);
        assertNotNull(terminalPromptRunner);
        assertNotNull(llamaServerManager);
        assertNotNull(conversationService);
        assertNotNull(conversationMemory);
        assertNotNull(tokenEstimator);
        assertNotNull(summarizer);
        assertNotNull(roleFlowConfig);
        assertNotNull(roleFlowEngine);
        assertNotNull(roleFlowSession);
        assertNotNull(goalFileWriter);
        assertNotNull(auditService);
        assertNotNull(auditController);
    }
}
