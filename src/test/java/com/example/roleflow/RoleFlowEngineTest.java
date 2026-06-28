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
        final Map<String, String> alwaysByRole = new LinkedHashMap<>();
        final List<String> rolesInvoked = new ArrayList<>();
        final List<String> promptsSeen = new ArrayList<>();

        ScriptedModel on(String role, String... replies) {
            Deque<String> queue = repliesByRole.computeIfAbsent(role, r -> new ArrayDeque<>());
            for (String reply : replies) queue.add(reply);
            return this;
        }

        /** Always returns {@code reply} for {@code role}, however many times it is called (e.g. a function). */
        ScriptedModel always(String role, String reply) {
            alwaysByRole.put(role, reply);
            return this;
        }

        @Override
        public String invoke(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature) {
            String role = roleFrom(systemPrompt);
            rolesInvoked.add(role);
            promptsSeen.add(userPrompt);
            if (alwaysByRole.containsKey(role)) {
                return alwaysByRole.get(role);
            }
            Deque<String> queue = repliesByRole.get(role);
            if (queue == null || queue.isEmpty()) {
                // Default: TopicAnalyzer finds no topics unless a test scripts it otherwise, so flow tests
                // proceed straight to SignalOrRequest without having to script the topic step.
                if ("TopicAnalyzer".equals(role)) return json("none", "no relevant topics");
                // Default: the per-step planner functions return a short detail, so flow tests that reach
                // StepReviewer do not have to script each function call (one per step).
                if (role.endsWith("Planner")) return json("return", "detail for the step");
                // Default: PlanDetailReviewer finds the plan sufficiently detailed, so flow tests go straight
                // to ResponseBuilder without entering the PlanDetailer refinement loop.
                if ("PlanDetailReviewer".equals(role)) return json("sufficient", "the plan is complete");
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

    // Plans must pass PlanValidator (the four phase headers, each with a step), so tests use real ones.
    // VALID_PLAN is embedded in JSON (\\n escapes); *_TEXT is the parsed form written to the file.
    private static final String VALID_PLAN =
            "## Phase 1 - Preparation\\n- Assumption: Python is available\\n- Decision: use Python\\n"
            + "- gather info\\n## Phase 2 - Action\\n- do the work\\n"
            + "## Phase 3 - Verification\\n- verify it\\n## Phase 4 - Next steps\\n- follow up";
    private static final String VALID_PLAN_TEXT = VALID_PLAN.replace("\\n", "\n");
    private static final String VALID_PLAN_V2 =
            "## Phase 1 - Preparation\\n- Assumption: tools are ready\\n- Decision: use Java\\n"
            + "- gather more info\\n## Phase 2 - Action\\n- do improved work\\n"
            + "## Phase 3 - Verification\\n- verify thoroughly\\n## Phase 4 - Next steps\\n- schedule a check";
    private static final String VALID_PLAN_V2_TEXT = VALID_PLAN_V2.replace("\\n", "\n");

    private RoleFlowConfig config;
    private RoleFlowSession session;
    private GoalFileWriter writer;
    private SessionLabeler labeler;
    private AuditService audit;
    private SkillRegistry skills;
    private RoleFlowEngine engine;

    @BeforeEach
    void setUp(@TempDir Path goalsDir) throws Exception {
        config = new RoleFlowConfig(RoleFlowConfig.parse(Files.readString(Path.of("config/roleflow.active"))));
        session = new RoleFlowSession();
        writer = new GoalFileWriter(goalsDir);
        labeler = new SessionLabeler(goalsDir.toString());
        audit = new AuditService(50);
        skills = new SkillRegistry(List.of(new MathematicsSkillProvider()));
        // No TopicResearcher by default; the dedicated web-context test supplies one.
        engine = new RoleFlowEngine(config, session, writer, labeler, audit, null, skills, new ObjectMapper(), 20);
        this.goalsDir = goalsDir;
    }

    private Path goalsDir;

    private long fileCount() throws Exception {
        if (!Files.exists(goalsDir)) return 0;
        try (var stream = Files.list(goalsDir)) {
            return stream.count();
        }
    }

    private long fileCount(String prefix) throws Exception {
        if (!Files.exists(goalsDir)) return 0;
        try (var stream = Files.list(goalsDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    void signalRunsClassifierThenResponderAndWritesNoFiles() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("signal", "This looks like a greeting"))
                .on("SignalResponse", json("done", "Hello to you too!"));

        String result = engine.run("Hello", null, null, null, model, null, "test");

        assertEquals("Hello to you too!", result);
        assertEquals(List.of("TopicAnalyzer", "SignalOrRequest", "SignalResponse"), model.rolesInvoked);
        assertTrue(session.isIdle(), "the run should complete and reset");
        assertEquals(0, fileCount(), "a signal must not create any files");
    }

    @Test
    void clearRequestRunsAllRolesAndWritesGoalAndPlan() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria: deliver a report"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal\\nDeliver a report"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "Plan reviewed; no changes needed."))
                .on("StepReviewer", json("continue", "Step 1: deliver -> action"))
                .on("ResponseBuilder", json("done", "Done: goal and plan created."));

        String result = engine.run("Build me a report", null, null, null, model, null, "test");

        // The model's confirmation comes first, then the engine appends the exact file link.
        assertTrue(result.startsWith("Done: goal and plan created."), result);
        assertTrue(result.contains("Files created:"), result);
        assertTrue(result.contains("Plan file: file:"), "the single plan file URL should be reported");
        assertFalse(result.contains("Goal file: file:"), "there is no separate goal file");
        assertTrue(result.contains("plan_"));
        // The file *contents* must not be pasted into the response.
        assertFalse(result.contains("Deliver a report"), "must report the location, not file contents");
        // The file name carries a human-readable prefix derived from the initial prompt.
        assertTrue(findFile("plan_build-report").getFileName().toString().startsWith("plan_build-report"),
                "plan file name should include the prompt-derived prefix");

        // StepReviewer is computed by the engine (no model call); the per-step planner FUNCTIONS it calls
        // are model-driven, so the main-flow roles run in order and the functions run in between.
        List<String> mainFlow = model.rolesInvoked.stream().filter(r -> !r.endsWith("Planner")).toList();
        assertEquals(List.of("TopicAnalyzer", "SignalOrRequest", "HandleRequest", "GoalBuilder",
                "PlanBuilder", "PlanReviewer", "PlanDetailReviewer", "ResponseBuilder"), mainFlow);
        assertTrue(model.rolesInvoked.stream().anyMatch(r -> r.endsWith("Planner")),
                "a planner function is called for each step");
        // The user prompt is unchanged across the whole auto-advancing run.
        assertTrue(model.promptsSeen.stream().allMatch("Build me a report"::equals));
        assertTrue(session.isIdle());

        // Exactly ONE file, holding the goal section, then the plan section, then per-step detail sections.
        assertEquals(1, fileCount(), "a request should create exactly one combined plan file");
        assertEquals(0, fileCount("goal_"), "the goal is no longer a separate file");
        String document = Files.readString(findFile("plan_"));
        assertTrue(document.startsWith(PlanDocument.compose("# Goal\nDeliver a report", VALID_PLAN_TEXT)),
                document);
        assertTrue(document.startsWith("# Goal\n\nDeliver a report"), document);
        assertTrue(document.contains("\n# Plan\n\n## Phase 1 - Preparation"), document);
        assertTrue(document.contains("# Step Details"), "per-step detail sections are appended");
        assertTrue(document.contains("## Step 1:"), document);
    }

    @Test
    void outputRoleWritesFileFromMessageWhenArtifactIsEmpty() throws Exception {
        // The model puts the plan in "message" and leaves "artifact" empty — the file must still be written.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria: deliver a report"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", json("continue", VALID_PLAN))   // plan in the message, artifact empty
                .on("PlanReviewer", json("ok", "Plan reviewed; no changes needed."))
                .on("StepReviewer", json("continue", "Step 1: prepare -> action"))
                .on("ResponseBuilder", json("done", "All done."));

        String result = engine.run("Build me a report", null, null, null, model, null, "test");

        assertTrue(Files.exists(findFile("plan_")), "the plan file should be written from the message");
        assertTrue(Files.readString(findFile("plan_"))
                .startsWith(PlanDocument.compose("# Goal", VALID_PLAN_TEXT)));
        // The single combined file is reported in the completed run.
        assertTrue(result.contains("Plan file: file:"), result);
        assertFalse(result.contains("Goal file: file:"), result);
    }

    @Test
    void recoversAndNormalizesAPlanFromDuplicateKeyJsonWithLoosePhaseFormatting() throws Exception {
        // Reproduces a real 3B failure on the Legendre prompt: the model emitted the plan in the FIRST
        // "artifact" but appended an empty duplicate ("artifact":""), and styled the plan as "# Phase 1"
        // headers with "## Assumption:" sub-headings. The reply parser must keep the first artifact and the
        // engine must normalize it into the canonical four-phase structure instead of writing the prose
        // "message" paragraph.
        String loosePlan = "# Phase 1\\n## Assumption: A language is needed.\\n## Decision: Use Python.\\n"
                + "# Phase 2\\n## Action: Install Python.\\n"
                + "# Phase 3\\n## Confirmation: Run it.\\n"
                + "# Phase 4\\n## Follow-Up: Document results.";
        String duplicateKeyReply = "{\"message\":\"The high-level plan is outlined. Python will be used.\","
                + "\"decision\":\"continue\",\"artifact\":\"" + loosePlan + "\","
                + "\"decision\":\"continue\",\"artifact\":\"\"}";

        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", duplicateKeyReply)
                .on("PlanReviewer", json("ok", "Plan reviewed; no changes needed."))
                .on("ResponseBuilder", json("done", "All done."));

        engine.run("Search the first 10000 integers for a counterexample to Legendre's conjecture",
                null, null, null, model, null, "test");

        String plan = Files.readString(findFile("plan_"));
        // The written plan is the canonical four-phase structure, NOT the prose message.
        assertTrue(PlanValidator.isValid(plan), () -> "written plan must be valid but was:\n" + plan);
        assertTrue(plan.contains("## Phase 1 - Preparation"), plan);
        assertTrue(plan.contains("- Assumption: A language is needed."), plan);
        assertTrue(plan.contains("- Decision: Use Python."), plan);
        assertFalse(plan.contains("high-level plan is outlined"), "the prose message must not be the plan");
        assertFalse(plan.contains("# Phase 1\n"), "loose headers must be normalized away");
    }

    @Test
    void reportingRoleSeesFileLocationsButNotContents() throws Exception {
        // GoalBuilder (an output role) is given prior content; ResponseBuilder (a reporting role) is not.
        session.begin("GoalBuilder", "build-report_20260101-000000", "build me a report");
        session.addArtifact("goal", "SECRET GOAL BODY", "file:///tmp/goal-1.md");

        String reportingPrompt = engine.buildSystemPrompt(config.byName("ResponseBuilder"), null);
        assertTrue(reportingPrompt.contains("file:///tmp/goal-1.md"), "reporting role should see the location");
        assertFalse(reportingPrompt.contains("SECRET GOAL BODY"), "reporting role must not see file contents");
        assertTrue(reportingPrompt.contains("Report these file locations to the user"));

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
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "Plan reviewed; no changes needed."))
                .on("StepReviewer", json("continue", "Step 1: produce report -> action"))
                .on("ResponseBuilder", json("done", "All set."));

        // First prompt: classifier + clarifier, then pause waiting for the user.
        String question = engine.run("Make me a report", null, null, null, model, null, "test");
        assertEquals("What format do you want the report in?", question);
        assertEquals(List.of("TopicAnalyzer", "SignalOrRequest", "HandleRequest"), model.rolesInvoked);
        assertFalse(session.isIdle(), "should be paused mid-flow at the clarifier");
        assertEquals("HandleRequest", session.currentRole());
        assertEquals(0, fileCount(), "no files until the request is clarified");

        // The user's answer resumes at the clarifier and runs to completion.
        String result = engine.run("A PDF, please", null, null, null, model, null, "test");
        assertTrue(result.startsWith("All set."), result);
        assertTrue(result.contains("Files created:") && result.contains("file:"), result);
        assertTrue(session.isIdle());
        assertEquals(1, fileCount(), "one combined goal-and-plan file");
    }

    @Test
    void cancelledRequestEndsWithoutFiles() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("cancelled", "Okay, I've cancelled that."));

        String result = engine.run("Actually never mind", null, null, null, model, null, "test");

        assertEquals("Okay, I've cancelled that.", result);
        assertEquals(List.of("TopicAnalyzer", "SignalOrRequest", "HandleRequest"), model.rolesInvoked);
        assertTrue(session.isIdle());
        assertEquals(0, fileCount());
    }

    @Test
    void unknownTransitionTargetCompletesTheRun() throws Exception {
        RoleFlowConfig tiny = new RoleFlowConfig(RoleFlowConfig.parse(
                "1. Start\nRole: x\nAction: do it\nTransition: go -> Ghost\n"));
        RoleFlowEngine tinyEngine = new RoleFlowEngine(
                tiny, session, writer, labeler, audit, null, skills, new ObjectMapper(), 20);
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
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "Plan reviewed; no changes needed."))
                .on("StepReviewer", json("continue", "Step 1: deliver -> action"))
                .on("ResponseBuilder", json("done", "All done."));

        engine.run("Build me a report", null, null, null, model, "PID-1", "web");

        AuditView view = audit.viewByPrompt("PID-1");
        assertTrue(view.completed(), "the run should be marked completed");
        List<AuditEvent> events = view.events();

        // Each role is recorded with its prompt, system prompt, and response. The first model role is
        // TopicAnalyzer.
        AuditEvent firstRequest = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.MODEL_REQUEST)
                .findFirst().orElseThrow();
        assertEquals("TopicAnalyzer", firstRequest.role());
        assertEquals("Build me a report", firstRequest.userPrompt());
        assertTrue(firstRequest.systemPrompt().contains("Current role: TopicAnalyzer"),
                "the system prompt used should be captured");

        assertTrue(hasType(events, AuditEvent.Type.RUN_STARTED));
        // Counting only the MAIN-FLOW roles (ignoring the per-step planner functions): nine roles run
        // (TopicAnalyzer found no topics, so TopicContextBuilder is skipped; PlanDetailReviewer finds the
        // plan sufficient, so PlanDetailer is skipped): topic, classifier, clarifier, goal, plan,
        // plan-review, step-review, detail-review, response.
        assertEquals(9, mainFlowCount(events, AuditEvent.Type.ROLE_STARTED), "one ROLE_STARTED per role");
        // StepReviewer is computed, so it has no MODEL_RESPONSE — only the eight model-driven roles do.
        assertEquals(8, mainFlowCount(events, AuditEvent.Type.MODEL_RESPONSE));
        assertEquals(9, mainFlowCount(events, AuditEvent.Type.ROLE_RESULT), "each role's result is recorded");
        // The transition handling is called out, including the final "-> done".
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.TRANSITION
                && e.transition().contains("request") && e.transition().contains("HandleRequest")));
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.TRANSITION
                && e.transition().contains("done")));
        // One combined plan file is written by the plan roles (the goal has no file of its own; the reviewer
        // made no change). The per-step functions then rewrite the same file with their detail sections.
        assertEquals(1, mainFlowCount(events, AuditEvent.Type.ARTIFACT_WRITTEN));
        // The per-step planner functions are recorded in the audit trail just like roles.
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.ROLE_STARTED
                && e.role().endsWith("Planner")), "function calls are audited like roles");
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.ARTIFACT_WRITTEN
                && e.role().endsWith("Planner")), "functions write their step detail to the plan file");
        assertTrue(hasType(events, AuditEvent.Type.RUN_COMPLETED));
    }

    /** Counts audit events of a type contributed by the main-flow roles (excluding the planner functions). */
    private static long mainFlowCount(List<AuditEvent> events, AuditEvent.Type type) {
        return events.stream()
                .filter(e -> e.type() == type && (e.role() == null || !e.role().endsWith("Planner")))
                .count();
    }

    @Test
    void stepReviewerCallsAFunctionPerStepThatAddsDetailSectionsToThePlanFile() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "no change"))
                .on("ResponseBuilder", json("done", "Done."))
                // The four planner functions are called once per matching step; give each a distinct detail.
                .always("DecisionPlanner", json("return", "DECISION-DETAIL: use Python"))
                .always("ActionPlanner", json("return", "ACTION-DETAIL: write code"))
                .always("InformationPlanner", json("return", "INFO-DETAIL: request from web"));

        engine.run("Build me a report", null, null, null, model, "PID-fn", "web");

        String document = Files.readString(findFile("plan_"));
        // The high-level plan is unchanged; a "# Step Details" section with one subsection per step is added.
        assertTrue(document.contains("# Plan\n\n## Phase 1 - Preparation"), document);
        assertTrue(document.contains("# Step Details"), document);
        assertTrue(document.contains("## Step 1:"), document);
        assertTrue(document.contains("decision-point"), document);   // category appears in a step heading
        // The detail bodies come from the functions mapped to each step's category.
        assertTrue(document.contains("DECISION-DETAIL: use Python"), document);
        assertTrue(document.contains("ACTION-DETAIL: write code"), document);
        assertTrue(document.contains("INFO-DETAIL: request from web"), document);

        // Each function call is recorded in the audit trail like a role, and returns to its caller.
        List<AuditEvent> events = audit.viewByPrompt("PID-fn").events();
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.ROLE_STARTED
                && "DecisionPlanner".equals(e.role())));
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.MODEL_REQUEST
                && "DecisionPlanner".equals(e.role())));
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.ROLE_RESULT
                && "DecisionPlanner".equals(e.role())));
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.ARTIFACT_WRITTEN
                && "DecisionPlanner".equals(e.role())));
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.TRANSITION
                && "DecisionPlanner".equals(e.role()) && e.transition().contains("return -> StepReviewer")),
                "a function returns to its caller rather than transitioning onward");

        // The function's prompt tells the model to describe the step directly, not narrate "The first step is".
        AuditEvent functionRequest = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.MODEL_REQUEST && "DecisionPlanner".equals(e.role()))
                .findFirst().orElseThrow();
        assertTrue(functionRequest.systemPrompt().contains("The first step is"),
                "the prompt should explicitly steer away from \"The first step is\" narration");
        assertTrue(functionRequest.systemPrompt().contains("Describe it DIRECTLY"),
                functionRequest.systemPrompt());
    }

    @Test
    void planDetailReviewerLoopsThroughPlanDetailerToAddRefinementSections() throws Exception {
        String todos = "ambiguous: install Python\\ntoo high level: build the app";
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "no change"))
                // First review flags two steps; after PlanDetailer refines them, the second review passes.
                .on("PlanDetailReviewer",
                        jsonWithArtifact("update", "needs work", todos),
                        json("sufficient", "now complete"))
                .always("SubgoalPlanner", json("return", "SUBGOAL-DETAIL: do the substeps"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-detail", "web");

        String document = Files.readString(findFile("plan_"));
        // PlanDetailer added one refinement section per TODO item, via SubgoalPlanner.
        assertTrue(document.contains("## Refinement: install Python — ambiguous"), document);
        assertTrue(document.contains("## Refinement: build the app — too-high-level"), document);
        assertTrue(document.contains("SUBGOAL-DETAIL: do the substeps"), document);

        List<AuditEvent> events = audit.viewByPrompt("PID-detail").events();
        // PlanDetailReviewer ran twice (update, then sufficient); PlanDetailer ran once.
        assertEquals(2, events.stream().filter(e -> e.type() == AuditEvent.Type.ROLE_STARTED
                && "PlanDetailReviewer".equals(e.role())).count());
        assertEquals(1, events.stream().filter(e -> e.type() == AuditEvent.Type.ROLE_STARTED
                && "PlanDetailer".equals(e.role())).count());
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && "PlanDetailer".equals(e.role()) && e.detail().contains("iteration 1")));
        // PlanDetailer passes the issue-specific extra instruction to SubgoalPlanner.
        List<String> subgoalPrompts = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.MODEL_REQUEST && "SubgoalPlanner".equals(e.role()))
                .map(AuditEvent::systemPrompt).toList();
        assertTrue(subgoalPrompts.stream().anyMatch(p -> p.contains("AMBIGUOUS")), subgoalPrompts.toString());
        assertTrue(subgoalPrompts.stream().anyMatch(p -> p.contains("TOO HIGH-LEVEL")),
                subgoalPrompts.toString());
    }

    @Test
    void planDetailerStopsAfterThreeIterations() throws Exception {
        // PlanDetailReviewer always wants an update; the iteration cap must end the loop, not run forever.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "no change"))
                .always("PlanDetailReviewer", jsonWithArtifact("update", "needs work", "ambiguous: refine this"))
                .always("SubgoalPlanner", json("return", "more detail"))
                .on("ResponseBuilder", json("done", "Done."));

        String result = engine.run("Build me a report", null, null, null, model, "PID-cap", "web");

        List<AuditEvent> events = audit.viewByPrompt("PID-cap").events();
        // PlanDetailer runs at most three times, then the run exits to ResponseBuilder.
        assertEquals(3, events.stream().filter(e -> e.type() == AuditEvent.Type.ROLE_STARTED
                && "PlanDetailer".equals(e.role())).count());
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && "PlanDetailer".equals(e.role()) && e.detail().contains("reached the limit")));
        assertTrue(model.rolesInvoked.contains("ResponseBuilder"), "the run still reaches the responder");
        assertTrue(session.isIdle());
        assertTrue(result.startsWith("Done."), result);
    }

    @Test
    void auditCapturesClarificationIterations() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest",
                        json("unclear", "Which folder should I back up?"),
                        json("clear", "Criteria: back up the notes folder"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "Plan reviewed; no changes needed."))
                .on("StepReviewer", json("continue", "Step 1: back up -> action"))
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

    @Test
    void planReviewerLoopsOnChangeThenWritesUpdatedPlan() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria: deliver a report"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer",
                        jsonWithArtifact("change", "Strengthened verification phase", VALID_PLAN_V2),
                        json("ok", "Now the plan is complete."))
                .on("StepReviewer", json("continue", "Step 1: deliver -> action"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-rev", "web");

        // The reviewer re-reviews its own updated plan: PlanReviewer ran twice with no user input.
        assertEquals(2, model.rolesInvoked.stream().filter("PlanReviewer"::equals).count());
        // The plan file holds the reviewer's updated version (same file, overwritten).
        assertTrue(Files.readString(findFile("plan_"))
                .startsWith(PlanDocument.compose("# Goal", VALID_PLAN_V2_TEXT)));

        AuditView view = audit.viewByPrompt("PID-rev");
        assertTrue(view.events().stream().anyMatch(e -> e.type() == AuditEvent.Type.TRANSITION
                && e.transition().contains("change") && e.transition().contains("PlanReviewer")),
                "the auto re-review transition should be in the audit");
        // Two writes by the plan roles: the original plan, then the reviewer's updated plan (the goal has no
        // file). The step-detail functions rewrite the same file afterwards, so count only the plan roles.
        long planRoleWrites = view.events().stream()
                .filter(e -> e.type() == AuditEvent.Type.ARTIFACT_WRITTEN && !e.role().endsWith("Planner"))
                .count();
        assertEquals(2, planRoleWrites);
        // On disk there is one file (the plan and its step details were overwritten in place).
        assertEquals(1, fileCount());
    }

    @Test
    void planReviewerOkLeavesTheExistingPlanUnchanged() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "Reviewed; no changes needed."))
                .on("StepReviewer", json("continue", "Step 1 -> action"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, null, "test");

        // Conditional output: a "no change" review must NOT overwrite the plan with its message.
        assertTrue(Files.readString(findFile("plan_"))
                .startsWith(PlanDocument.compose("# Goal", VALID_PLAN_TEXT)));
    }

    @Test
    void planBuilderIsRetriedUntilTheStructureIsValid() throws Exception {
        // First attempt restates the goal (no phases) and is rejected; the engine re-prompts with feedback.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder",
                        jsonWithArtifact("continue", "bad plan", "# Goal\\n- just restated the goal"),
                        jsonWithArtifact("continue", "good plan", VALID_PLAN))
                .on("PlanReviewer", json("ok", "looks good"))
                .on("StepReviewer", json("continue", "Step 1 -> action"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-val", "web");

        // PlanBuilder was invoked twice: rejected once, then accepted.
        assertEquals(2, model.rolesInvoked.stream().filter("PlanBuilder"::equals).count());
        // Only the valid plan was written (the bad first attempt never reached the file).
        assertTrue(Files.readString(findFile("plan_"))
                .startsWith(PlanDocument.compose("# Goal", VALID_PLAN_TEXT)));

        List<AuditEvent> events = audit.viewByPrompt("PID-val").events();
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && e.detail().contains("rejected")), "the rejection should be in the audit trail");
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && e.detail().contains("valid")), "the eventual pass should be in the audit trail");
    }

    @Test
    void planBuilderProceedsAfterMaxAttemptsWhenStillInvalid() throws Exception {
        String badPlan = jsonWithArtifact("continue", "bad plan", "# Goal\\n- no phases here");
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", badPlan, badPlan, badPlan)   // invalid on all three attempts
                .on("PlanReviewer", json("ok", "ok"))
                .on("StepReviewer", json("continue", "Step 1 -> action"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-max", "web");

        // The engine stops re-prompting after three attempts and proceeds.
        assertEquals(3, model.rolesInvoked.stream().filter("PlanBuilder"::equals).count());
        assertTrue(audit.viewByPrompt("PID-max").events().stream()
                .anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                        && e.detail().contains("still invalid after 3 attempts")));
        assertTrue(session.isIdle(), "the run still completes");
    }

    @Test
    void conditionalOutputDoesNotRewriteWhenTheReviewerEchoesTheSamePlan() throws Exception {
        // The reviewer returns the identical plan content; the engine must not write the file again.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", jsonWithArtifact("ok", "no change", VALID_PLAN))   // echoes the plan
                .on("StepReviewer", json("continue", "Step 1: x -> action"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-echo", "web");

        // Only the plan roles' single write counts — the goal is not a separate file, and the reviewer's
        // identical echo must not trigger a rewrite (the step-detail functions rewrite the file separately).
        long planRoleWrites = audit.viewByPrompt("PID-echo").events().stream()
                .filter(e -> e.type() == AuditEvent.Type.ARTIFACT_WRITTEN && !e.role().endsWith("Planner"))
                .count();
        assertEquals(1, planRoleWrites);
        assertTrue(Files.readString(findFile("plan_"))
                .startsWith(PlanDocument.compose("# Goal", VALID_PLAN_TEXT)));
    }

    @Test
    void planReviewerProceedsToStepReviewerOnAnUnrecognizedDecision() throws Exception {
        // Small models sometimes return "continue" here instead of "change"/"ok". The default
        // (bare "StepReviewer") transition must carry the workflow forward, not end the run early.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", jsonWithArtifact("continue", "echoed the plan", VALID_PLAN))  // unexpected
                .on("StepReviewer", json("continue", "Step 1: deliver -> action"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-fallback", "web");

        // StepReviewer is computed (no model call); ignoring the per-step planner functions, the main flow
        // still reaches ResponseBuilder via the default transition.
        List<String> mainFlow = model.rolesInvoked.stream().filter(r -> !r.endsWith("Planner")).toList();
        assertEquals(List.of("TopicAnalyzer", "SignalOrRequest", "HandleRequest", "GoalBuilder",
                "PlanBuilder", "PlanReviewer", "PlanDetailReviewer", "ResponseBuilder"), mainFlow);
        assertTrue(session.isIdle(), "the run should complete only after ResponseBuilder");
        AuditView view = audit.viewByPrompt("PID-fallback");
        assertTrue(view.events().stream().anyMatch(e -> e.type() == AuditEvent.Type.TRANSITION
                && "PlanReviewer".equals(e.role()) && e.transition().contains("StepReviewer")),
                "PlanReviewer should transition to StepReviewer despite the unexpected decision");
    }

    @Test
    void stepReviewerClassifiesPlanStepsDeterministicallyWithoutAModelCall() throws Exception {
        // Deliberately no StepReviewer reply is scripted: it must not call the model.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "no change"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-steps", "web");

        assertFalse(model.rolesInvoked.contains("StepReviewer"), "StepReviewer must not call the model");

        List<AuditEvent> events = audit.viewByPrompt("PID-steps").events();
        AuditEvent stepResult = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.ROLE_RESULT && "StepReviewer".equals(e.role()))
                .findFirst().orElseThrow();
        // Each of VALID_PLAN's steps is classified, one per line.
        assertTrue(stepResult.detail().contains("Step 1:"), stepResult.detail());
        assertTrue(stepResult.detail().contains("Step 4:"), stepResult.detail());
        // VALID_PLAN's Phase 1 opens with an "Assumption:" and a "Decision:" — both decision points.
        assertTrue(stepResult.detail().contains("-> decision-point"), stepResult.detail());
        assertTrue(stepResult.detail().contains("-> request-for-information")
                || stepResult.detail().contains("-> action"), stepResult.detail());
        // A VALIDATION event records that the classification ran.
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && "StepReviewer".equals(e.role()) && e.detail().contains("classified")));
    }

    /** A web_search tool that records each query and returns a canned, topic-relevant snippet. */
    private static RoleFlowEngine engineWithWebSearch(RoleFlowConfig config, RoleFlowSession session,
            GoalFileWriter writer, SessionLabeler labeler, AuditService audit, List<String> queriesSeen) {
        ToolRegistry tools = new ToolRegistry(List.of(() -> List.of(new Tool(
                "web_search", "search", Map.of("type", "object"), "test:web_search", args -> {
            if (queriesSeen != null) queriesSeen.add(String.valueOf(args.get("query")));
            return "{\"results\":[{\"title\":\"" + args.get("query") + "\",\"snippet\":\"A counterexample "
                    + "would disprove the conjecture; finding one is a complete result, not a defect.\"}]}";
        }))));
        return new RoleFlowEngine(config, session, writer, labeler, audit,
                new TopicResearcher(tools, new ObjectMapper(), 3),
                new SkillRegistry(List.of(new MathematicsSkillProvider())), new ObjectMapper(), 20);
    }

    @Test
    void topicContextBuilderSearchesEveryIdentifiedTopicAndPlanReviewerUsesIt() throws Exception {
        List<String> queries = new ArrayList<>();
        RoleFlowEngine researchEngine = engineWithWebSearch(config, session, writer, labeler, audit, queries);

        ScriptedModel model = new ScriptedModel()
                .on("TopicAnalyzer", jsonWithArtifact("topics", "math, programming, Legendre",
                        "mathematics\nprogramming\nLegendre's conjecture"))
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "looks good"))
                .on("ResponseBuilder", json("done", "Done."));

        String result = researchEngine.run(
                "Search the first 10000 integers for a counterexample to Legendre's conjecture",
                null, null, null, model, "PID-web", "web");

        // EVERY identified topic was searched, in order — no topic is missed.
        assertEquals(List.of("mathematics", "programming", "Legendre's conjecture"), queries);

        List<AuditEvent> events = audit.viewByPrompt("PID-web").events();
        // TopicContextBuilder recorded gathering each topic, INCLUDING the details retrieved.
        assertEquals(3, events.stream().filter(e -> e.type() == AuditEvent.Type.VALIDATION
                && "TopicContextBuilder".equals(e.role())
                && e.detail().contains("gathered web context")).count());
        // The actual details (the canned snippet) are in the audit, not just the topic name.
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && "TopicContextBuilder".equals(e.role())
                && e.detail().contains("gathered web context for topic 'mathematics'")
                && e.detail().contains("A counterexample would disprove")),
                "the gathered details for each topic must appear in the audit trail");

        // PlanReviewer's prompt includes the gathered topic context.
        AuditEvent planReviewerRequest = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.MODEL_REQUEST && "PlanReviewer".equals(e.role()))
                .findFirst().orElseThrow();
        assertTrue(planReviewerRequest.systemPrompt().contains("Topic context"),
                planReviewerRequest.systemPrompt());
        assertTrue(planReviewerRequest.systemPrompt().contains("A counterexample would disprove"),
                planReviewerRequest.systemPrompt());

        // The identified topics are surfaced in the final response.
        assertTrue(result.contains("Topics considered: mathematics, programming, Legendre's conjecture"),
                result);
    }

    @Test
    void noTopicsSkipsContextBuildingAndAddsNoTopicsFooter() throws Exception {
        List<String> queries = new ArrayList<>();
        RoleFlowEngine researchEngine = engineWithWebSearch(config, session, writer, labeler, audit, queries);

        // TopicAnalyzer (default in ScriptedModel) returns "none" → TopicContextBuilder is skipped.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("signal", "greeting"))
                .on("SignalResponse", json("done", "Hello!"));

        String result = researchEngine.run("hello", null, null, null, model, "PID-none", "web");

        assertTrue(queries.isEmpty(), "no web searches when there are no topics");
        assertFalse(model.rolesInvoked.contains("TopicContextBuilder"));
        assertEquals("Hello!", result, "no 'Topics considered' footer when there are no topics");
    }

    @Test
    void planBuilderAppliesTheMathematicsSkillWhenMathematicsIsAnIdentifiedTopic() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("TopicAnalyzer", jsonWithArtifact("topics", "mathematics", "mathematics"))
                .on("TopicContextBuilder", json("continue", "n/a")) // not invoked (computed), kept for clarity
                .on("SignalOrRequest", json("request", "a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "no changes"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Search the first 10000 integers for a counterexample to Legendre's conjecture",
                null, null, null, model, "PID-skill", "web");

        List<AuditEvent> events = audit.viewByPrompt("PID-skill").events();
        AuditEvent planBuilderRequest = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.MODEL_REQUEST && "PlanBuilder".equals(e.role()))
                .findFirst().orElseThrow();
        // The mathematics skill guidance is injected into PlanBuilder's system prompt.
        assertTrue(planBuilderRequest.systemPrompt().contains("### Skill: mathematics"),
                planBuilderRequest.systemPrompt());
        assertTrue(planBuilderRequest.systemPrompt().contains("complete"), planBuilderRequest.systemPrompt());
        // The use of the skill is recorded in the audit trail.
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && "PlanBuilder".equals(e.role()) && e.detail().contains("applied skill(s): mathematics")),
                "the applied skill must appear in the audit trail");
    }

    @Test
    void planBuilderDoesNotApplyTheMathematicsSkillForANonMathematicsRequest() throws Exception {
        // Default TopicAnalyzer returns "none", so no topic matches the skill — it must not be injected.
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "no changes"))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Set up a weekly backup of my notes folder", null, null, null, model, "PID-noskill", "web");

        List<AuditEvent> events = audit.viewByPrompt("PID-noskill").events();
        AuditEvent planBuilderRequest = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.MODEL_REQUEST && "PlanBuilder".equals(e.role()))
                .findFirst().orElseThrow();
        assertFalse(planBuilderRequest.systemPrompt().contains("Skill: mathematics"),
                "math skill must not be injected when mathematics is not a topic");
        assertFalse(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.VALIDATION
                && e.detail() != null && e.detail().contains("applied skill")));
    }

    @Test
    void reviewerResultsAreVisibleInTheAuditTrail() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .on("SignalOrRequest", json("request", "This is a request"))
                .on("HandleRequest", json("clear", "Criteria"))
                .on("GoalBuilder", jsonWithArtifact("continue", "goal built", "# Goal"))
                .on("PlanBuilder", jsonWithArtifact("continue", "plan built", VALID_PLAN))
                .on("PlanReviewer", json("ok", "Reviewed the plan; no changes needed."))
                .on("ResponseBuilder", json("done", "Done."));

        engine.run("Build me a report", null, null, null, model, "PID-rev2", "web");

        List<AuditEvent> events = audit.viewByPrompt("PID-rev2").events();
        // The PlanReviewer's verdict is in the trail.
        assertTrue(events.stream().anyMatch(e -> e.type() == AuditEvent.Type.ROLE_RESULT
                && "PlanReviewer".equals(e.role()) && e.detail().contains("no changes needed")));
        // The StepReviewer's per-step classifications (computed in code) are in the trail. VALID_PLAN's
        // first step "gather info" is classified as request-for-information.
        AuditEvent stepResult = events.stream()
                .filter(e -> e.type() == AuditEvent.Type.ROLE_RESULT && "StepReviewer".equals(e.role()))
                .findFirst().orElseThrow();
        assertTrue(stepResult.detail().contains("Step 1:"), stepResult.detail());
        assertTrue(stepResult.detail().contains("-> request-for-information"), stepResult.detail());
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
