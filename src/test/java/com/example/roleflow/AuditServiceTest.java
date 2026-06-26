package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditServiceTest {

    @Test
    void recordsEventsInOrderAndRetrievesByRun() {
        AuditService audit = new AuditService(50);

        audit.runStarted("run-1", "web");
        audit.roleStarted("run-1", "Classifier");
        audit.modelRequest("run-1", "Classifier", "sys", "hello");
        audit.modelResponse("run-1", "Classifier", "raw reply", "signal");
        audit.transition("run-1", "Classifier", "decision 'signal' -> SignalResponse");
        audit.runCompleted("run-1", "SignalResponse");

        AuditView view = audit.viewByRun("run-1");
        assertEquals("run-1", view.runId());
        assertEquals("web", view.source());
        assertTrue(view.completed());

        List<AuditEvent> events = view.events();
        assertEquals(AuditEvent.Type.RUN_STARTED, events.get(0).type());
        assertEquals(AuditEvent.Type.RUN_COMPLETED, events.get(events.size() - 1).type());
        // Sequence numbers increase.
        assertEquals(1, events.get(0).seq());
        assertEquals(events.size(), events.get(events.size() - 1).seq());

        AuditEvent request = events.get(2);
        assertEquals("sys", request.systemPrompt());
        assertEquals("hello", request.userPrompt());
    }

    @Test
    void linksPromptIdToRunAndReturnsPendingWhenUnknown() {
        AuditService audit = new AuditService(50);
        audit.runStarted("run-2", "web");
        audit.link("prompt-2", "run-2");

        AuditView linked = audit.viewByPrompt("prompt-2");
        assertEquals("prompt-2", linked.promptId());
        assertEquals("run-2", linked.runId());

        AuditView pending = audit.viewByPrompt("never-seen");
        assertNull(pending.runId());
        assertFalse(pending.completed());
        assertTrue(pending.events().isEmpty());
    }

    @Test
    void roleStartedReturnsIncrementingIterationPerRole() {
        AuditService audit = new AuditService(50);
        audit.runStarted("run-3", "web");

        assertEquals(1, audit.roleStarted("run-3", "HandleRequest"));
        audit.roleStarted("run-3", "GoalBuilder");
        assertEquals(2, audit.roleStarted("run-3", "HandleRequest"));
    }

    @Test
    void completedOnlyAfterRunCompleted() {
        AuditService audit = new AuditService(50);
        audit.runStarted("run-4", "terminal");
        audit.clarificationPause("run-4", "HandleRequest", "Which folder?");

        assertFalse(audit.viewByRun("run-4").completed(), "a paused run is not complete");

        audit.runCompleted("run-4", "ResponseBuilder");
        assertTrue(audit.viewByRun("run-4").completed());
    }

    @Test
    void recentReturnsNewestFirst() {
        AuditService audit = new AuditService(50);
        audit.runStarted("run-a", "web");
        audit.runStarted("run-b", "web");

        List<AuditView> recent = audit.recent();
        assertEquals("run-b", recent.get(0).runId());
        assertEquals("run-a", recent.get(1).runId());
    }

    @Test
    void evictsOldestTrailsBeyondCapacity() {
        AuditService audit = new AuditService(2);
        audit.runStarted("r1", "web");
        audit.runStarted("r2", "web");
        audit.runStarted("r3", "web"); // evicts r1

        assertNull(audit.viewByRun("r1"));
        assertTrue(audit.viewByRun("r2") != null && audit.viewByRun("r3") != null);
    }

    @Test
    void formatsEventsAsReadableLogLines() {
        AuditEvent request = new AuditEvent(2, "2026-06-25T10:00:00Z", AuditEvent.Type.MODEL_REQUEST,
                "Classifier", null, "the system prompt", "the user prompt", null, null, null, null);
        String line = AuditService.format("run-x", request);

        assertTrue(line.contains("[audit run=run-x #2]"));
        assertTrue(line.contains("MODEL_REQUEST"));
        assertTrue(line.contains("system-prompt: the system prompt"));
        assertTrue(line.contains("user-prompt: the user prompt"));

        AuditEvent transition = new AuditEvent(3, "2026-06-25T10:00:01Z", AuditEvent.Type.TRANSITION,
                "Classifier", null, null, null, null, "request", "decision 'request' -> HandleRequest", null);
        assertTrue(AuditService.format("run-x", transition).contains("decision 'request' -> HandleRequest"));
    }
}
