package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleFlowEngineTest {

    /**
     * A scripted model: returns the next queued JSON reply for whichever role's system prompt it is given.
     * Records the (role, userPrompt) of every call so tests can assert call counts and that the user
     * prompt is unchanged across an auto-advancing run.
     */
    private static class ScriptedModel implements RoleFlowEngine.ModelInvoker {
        final Map<String, Deque<String>> repliesByRole = new LinkedHashMap<>();
        final List<String> rolesInvoked = new ArrayList<>();
        final List<String> promptsSeen = new ArrayList<>();

        ScriptedModel on(String role, String... replies) {
            Deque<String> queue = repliesByRole.computeIfAbsent(role, r -> new ArrayDeque<>());
            for (String reply : replies) queue.add(reply);
            return this;
        }

        @Override
        public String invoke(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature) {
            String role = roleFrom(systemPrompt);
            rolesInvoked.add(role);
            promptsSeen.add(userPrompt);
            Deque<String> queue = repliesByRole.get(role);
            if (queue == null || queue.isEmpty()) {
                throw new IllegalStateException("no scripted reply for role " + role);
            }
            return queue.poll();
        }

        private static String roleFrom(String systemPrompt) {
            int at = systemPrompt.indexOf("Current role: ");
            if (at < 0) return "?";
            String rest = systemPrompt.substring(at + "Current role: ".length());
            int cut = rest.indexOf('\n');
            String line = (cut < 0 ? rest : rest.substring(0, cut)).trim();
            int paren = line.indexOf(" (");
            return paren < 0 ? line : line.substring(0, paren);
        }
    }

    private static String json(String decision, String message) {
        return "{\"message\":\"" + message + "\",\"decision\":\"" + decision + "\",\"artifact\":\"\"}";
    }

    private static String jsonWithArtifact(String decision, String message, String artifact) {
        return "{\"message\":\"" + message + "\",\"decision\":\"" + decision
                + "\",\"artifact\":\"" + artifact + "\"}";
    }

    private RoleFlowConfig config;
    private RoleFlowSession session;
    private GoalFileWriter writer;
    private AuditService audit;
    private RoleFlowEngine engine;

    @BeforeEach
    void setUp(@TempDir Path goalsDir) throws Exception {
        config = new RoleFlowConfig(RoleFlowConfig.parse(Files.readString(Path.of("config/roleflow.active"))));
        session = new RoleFlowSession();
        writer = new GoalFileWriter(goalsDir);
        audit = new AuditService(50);
        engine = new RoleFlowEngine(config, session, writer, audit, new ObjectMapper(), 20);
        this.goalsDir = goalsDir;
    }

    private Path goalsDir;

    private long fileCount() throws Exception {
        if (!Files.exists(goalsDir)) return 0;
        try (var stream = Files.list(goalsDir)) {
            return stream.count();
        }
    }

    @Test
    void signalRunsClassifierThenResponderAndWritesNoFiles() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("signal", "This looks like a greeting"))
                .on("SignalResponse", json("done", "Hello to you too!"));

        String result = engine.run("Hello", null, null, null, model, null, "test");

        assertEquals("Hello to you too!", result);
        assertEquals(List.of("SignalOrRequest", "SignalResponse"), model.rolesInvoked);
        assertTrue(session.isIdle(), "the run should complete and reset");
        assertEquals(0, fileCount(), "a signal must not create any files");
    }

    @Test
    void clearRequestRunsAllRolesAndWritesGoalAndPlan() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria: deliver a report"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal\\nDeliver a report"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", "# Plan\\nPhase 1..."))
                .on("ResponseBuilder", json("done", "Done: goal and plan created."));

        String result = engine.run("Build me a report", null, null, null, model, null, "test");

        // The model's confirmation comes first, then the engine appends the exact file links.
        assertTrue(result.startsWith("Done: goal and plan created."), result);
        assertTrue(result.contains("Files created:"), result);
        assertTrue(result.contains("Goal file: file:"), "the goal file URL should be reported");
        assertTrue(result.contains("Plan file: file:"), "the plan file URL should be reported");
        assertTrue(result.contains("goal-") && result.contains("plan-"));
        // The file *contents* must not be pasted into the response (the bug this fixes).
        assertFalse(result.contains("Deliver a report"), "must report locations, not file contents");

        assertEquals(List.of("SignalOrRequest", "HandleRequest", "GoalBuilder",
                "PlanBuilder", "ResponseBuilder"), model.rolesInvoked);
        // The user prompt is unchanged across the whole auto-advancing run.
        assertTrue(model.promptsSeen.stream().allMatch("Build me a report"::equals));
        assertTrue(session.isIdle());

        assertEquals(2, fileCount(), "a request should create exactly a goal file and a plan file");
        assertTrue(Files.exists(findFile("goal-")), "goal file should exist");
        assertTrue(Files.exists(findFile("plan-")), "plan file should exist");
        assertEquals("# Goal\nDeliver a report", Files.readString(findFile("goal-")));
    }

    @Test
    void outputRoleWritesFileFromMessageWhenArtifactIsEmpty() throws Exception {
        // The model puts the plan in "message" and leaves "artifact" empty — the file must still be written.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria: deliver a report"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", json("continue", "# Plan\\nPhase 1: prepare"))   // artifact empty
                .on("ResponseBuilder", json("done", "All done."));

        String result = engine.run("Build me a report", null, null, null, model, null, "test");

        assertTrue(Files.exists(findFile("plan-")), "the plan file should be written from the message");
        assertEquals("# Plan\nPhase 1: prepare", Files.readString(findFile("plan-")));
        // Both files are reported in the completed run.
        assertTrue(result.contains("Goal file: file:") && result.contains("Plan file: file:"), result);
    }

    @Test
    void reportingRoleSeesFileLocationsButNotContents() throws Exception {
        // GoalBuilder (an output role) is given prior content; ResponseBuilder (a reporting role) is not.
        session.begin("GoalBuilder");
        session.addArtifact("goal", "SECRET GOAL BODY", "file:///tmp/goal-1.md");

        String reportingPrompt = engine.buildSystemPrompt(config.byName("ResponseBuilder"), null);
        assertTrue(reportingPrompt.contains("file:///tmp/goal-1.md"), "reporting role should see the location");
        assertFalse(reportingPrompt.contains("SECRET GOAL BODY"), "reporting role must not see file contents");

        String outputPrompt = engine.buildSystemPrompt(config.byName("PlanBuilder"), null);
        assertTrue(outputPrompt.contains("SECRET GOAL BODY"), "an output role may use prior content");
    }

    @Test
    void unclearRequestPausesForClarificationThenResumes() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest",
                        json("unclear", "What format do you want the report in?"),
                        json("clear", "Criteria: a PDF report"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", "# Plan"))
                .on("ResponseBuilder", json("done", "All set."));

        // First prompt: classifier + clarifier, then pause waiting for the user.
        String question = engine.run("Make me a report", null, null, null, model, null, "test");
        assertEquals("What format do you want the report in?", question);
        assertEquals(List.of("SignalOrRequest", "HandleRequest"), model.rolesInvoked);
        assertFalse(session.isIdle(), "should be paused mid-flow at the clarifier");
        assertEquals("HandleRequest", session.currentRole());
        assertEquals(0, fileCount(), "no files until the request is clarified");

        // The user's answer resumes at the clarifier and runs to completion.
        String result = engine.run("A PDF, please", null, null, null, model, null, "test");
        assertTrue(result.startsWith("All set."), result);
        assertTrue(result.contains("Files created:") && result.contains("file:"), result);
        assertTrue(session.isIdle());
        assertEquals(2, fileCount());
    }

    @Test
    void cancelledRequestEndsWithoutFiles() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("cancelled", "Okay, I've cancelled that."));

        String result = engine.run("Actually never mind", null, null, null, model, null, "test");

        assertEquals("Okay, I've cancelled that.", result);
        assertEquals(List.of("SignalOrRequest", "HandleRequest"), model.rolesInvoked);
        assertTrue(session.isIdle());
        assertEquals(0, fileCount());
    }

    @Test
    void unknownTransitionTargetCompletesTheRun() throws Exception {
        RoleFlowConfig tiny = new RoleFlowConfig(RoleFlowConfig.parse(
                "1. Start\nRole: x\nAction: do it\nTransition: go -> Ghost\n"));
        RoleFlowEngine tinyEngine = new RoleFlowEngine(
                tiny, session, writer, audit, new ObjectMapper(), 20);
        ScriptedModel model = new ScriptedModel().on("Start", json("go", "went nowhere"));

        String result = tinyEngine.run("hi", null, null, null, model, null, "test");

        assertEquals("went nowhere", result);
        assertEquals(1, model.rolesInvoked.size());
        assertTrue(session.isIdle(), "a transition to a missing role ends the run");
    }

    @Test
    void recordsAFullAuditTrailForARequest() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria: deliver a report"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", "# Plan"))
                .on("ResponseBuilder", json("done", "All done."));

        engine.run("Build me a report", null, null, null, model, "PID-1", "web");

        AuditView view = audit.viewByPrompt("PID-1");
        assertTrue(view.completed(), "the run should be marked completed");
        List<AuditEvent> events = view.events();

        // Each role is recorded with its prompt, system prompt, and response.
        AuditEvent firstRequest = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.MODEL_REQUEST)
                .findFirst().orElseThrow();
        assertEquals("SignalOrRequest", firstRequest.role());
        assertEquals("Build me a report", firstRequest.userPrompt());
        assertTrue(firstRequest.systemPrompt().contains("Current role: SignalOrRequest"),
                "the system prompt used should be captured");

        assertTrue(hasType(events, AuditEvent.Type.RUN_STARTED));
        assertEquals(5, count(events, AuditEvent.Type.ROLE_STARTED), "one ROLE_STARTED per role");
        assertEquals(5, count(events, AuditEvent.Type.MODEL_RESPONSE));
        // The transition handling is called out, including the final "-> done".
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.TRANSITION
                && e.transition().contains("request") && e.transition().contains("HandleRequest")));
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.TRANSITION
                && e.transition().contains("done")));
        // Goal and plan artifacts are recorded.
        assertEquals(2, count(events, AuditEvent.Type.ARTIFACT_WRITTEN));
        assertTrue(hasType(events, AuditEvent.Type.RUN_COMPLETED));
    }

    @Test
    void auditCapturesClarificationIterations() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest",
                        json("unclear", "Which folder should I back up?"),
                        json("clear", "Criteria: back up the notes folder"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", "# Plan"))
                .on("ResponseBuilder", json("done", "All set."));

        // First submission pauses for clarification; the trail is not yet complete.
        engine.run("Back up my stuff", null, null, null, model, "PID-2", "web");
        AuditView paused = audit.viewByPrompt("PID-2");
        assertFalse(paused.completed(), "paused run should not be complete");
        assertTrue(hasType(paused.events(), AuditEvent.Type.CLARIFICATION_PAUSE));

        // The clarification answer resumes the SAME run; a second prompt id maps to the same trail.
        engine.run("My notes folder", null, null, null, model, "PID-2b", "web");
        AuditView resumed = audit.viewByPrompt("PID-2b");
        assertEquals(resumed.runId(), paused.runId(), "both prompts belong to the same run");
        assertTrue(resumed.completed());

        // HandleRequest was entered twice — the clarifying iteration is visible in the audit.
        long handleStarts = resumed.events().stream()
                .filter(e -> e.type() == AuditEvent.Type.ROLE_STARTED && "HandleRequest".equals(e.role()))
                .count();
        assertEquals(2, handleStarts, "the clarifying iteration should be recorded");
        assertTrue(resumed.events().stream().anyMatch(
                e -> e.type() == AuditEvent.Type.ROLE_STARTED && "HandleRequest".equals(e.role())
                        && e.iteration() != null && e.iteration() == 2));
    }

    private static boolean hasType(List<AuditEvent> events, AuditEvent.Type type) {
        return events.stream().anyMatch(e -> e.type() == type);
    }

    private static long count(List<AuditEvent> events, AuditEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    private Path findFile(String prefix) throws Exception {
        try (var stream = Files.list(goalsDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst().orElseThrow();
        }
    }
}
