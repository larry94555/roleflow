package com.example.roleflow;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditControllerTest {

    private AuditService populated() {
        AuditService audit = new AuditService(50);
        audit.runStarted("run-1", "web");
        audit.link("prompt-1", "run-1");
        audit.roleStarted("run-1", "Classifier");
        audit.runCompleted("run-1", "Classifier");
        return audit;
    }

    @Test
    void returnsTrailByPromptId() {
        AuditController controller = new AuditController(populated());

        AuditView view = controller.byPrompt("prompt-1");

        assertEquals("prompt-1", view.promptId());
        assertEquals("run-1", view.runId());
        assertTrue(view.completed());
    }

    @Test
    void returnsPendingViewForUnknownPromptId() {
        AuditController controller = new AuditController(populated());

        AuditView view = controller.byPrompt("unknown");

        assertNull(view.runId());
        assertTrue(view.events().isEmpty());
    }

    @Test
    void returnsTrailByRunId() {
        AuditController controller = new AuditController(populated());

        AuditView view = controller.byRun("run-1");

        assertEquals("run-1", view.runId());
    }

    @Test
    void unknownRunIdYieldsNotFound() {
        AuditController controller = new AuditController(populated());

        assertThrows(ResponseStatusException.class, () -> controller.byRun("missing"));
    }

    @Test
    void recentListsRuns() {
        AuditController controller = new AuditController(populated());

        assertEquals(1, controller.recent().size());
    }
}
