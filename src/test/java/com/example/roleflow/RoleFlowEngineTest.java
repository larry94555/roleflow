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
    private RoleFlowEngine engine;

    @BeforeEach
    void setUp(@TempDir Path goalsDir) throws Exception {
        config = new RoleFlowConfig(RoleFlowConfig.parse(Files.readString(Path.of("config/roleflow.active"))));
        session = new RoleFlowSession();
        writer = new GoalFileWriter(goalsDir);
        engine = new RoleFlowEngine(config, session, writer, new ObjectMapper(), 20);
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

        String result = engine.run("Hello", null, null, null, model);

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

        String result = engine.run("Build me a report", null, null, null, model);

        assertEquals("Done: goal and plan created.", result);
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
        String question = engine.run("Make me a report", null, null, null, model);
        assertEquals("What format do you want the report in?", question);
        assertEquals(List.of("SignalOrRequest", "HandleRequest"), model.rolesInvoked);
        assertFalse(session.isIdle(), "should be paused mid-flow at the clarifier");
        assertEquals("HandleRequest", session.currentRole());
        assertEquals(0, fileCount(), "no files until the request is clarified");

        // The user's answer resumes at the clarifier and runs to completion.
        String result = engine.run("A PDF, please", null, null, null, model);
        assertEquals("All set.", result);
        assertTrue(session.isIdle());
        assertEquals(2, fileCount());
    }

    @Test
    void cancelledRequestEndsWithoutFiles() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("cancelled", "Okay, I've cancelled that."));

        String result = engine.run("Actually never mind", null, null, null, model);

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
                tiny, session, writer, new ObjectMapper(), 20);
        ScriptedModel model = new ScriptedModel().on("Start", json("go", "went nowhere"));

        String result = tinyEngine.run("hi", null, null, null, model);

        assertEquals("went nowhere", result);
        assertEquals(1, model.rolesInvoked.size());
        assertTrue(session.isIdle(), "a transition to a missing role ends the run");
    }

    private Path findFile(String prefix) throws Exception {
        try (var stream = Files.list(goalsDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst().orElseThrow();
        }
    }
}
