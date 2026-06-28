package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleFlowConfigTest {

    private static final String SAMPLE = """
            # a comment line
            1. SignalOrRequest
            Role: Classifier
            Action:
              Classify the prompt.
              Two lines of action.
            Output: none
            Transition: signal -> SignalResponse, request -> HandleRequest

            2. SignalResponse
            Role: Responder
            Action: Respond to the signal.
            Output: none
            Transition: done

            3. HandleRequest
            Role: Clarifier
            Action: Clarify.
            Transition: clear -> GoalBuilder, unclear -> HandleRequest, cancelled -> done

            4. GoalBuilder
            Role: Goal author
            Action: Build the goal.
            Output: goal
            Transition: PlanBuilder
            """;

    @Test
    void parsesRolesFieldsAndOrder() {
        List<Role> roles = RoleFlowConfig.parse(SAMPLE);

        assertEquals(4, roles.size());
        Role first = roles.get(0);
        assertEquals(1, first.number());
        assertEquals("SignalOrRequest", first.name());
        assertEquals("Classifier", first.title());
        assertTrue(first.action().contains("Classify the prompt."));
        assertTrue(first.action().contains("Two lines of action."), "multi-line Action should be captured");
        assertFalse(first.hasOutput());
    }

    @Test
    void parsesOutputKind() {
        Role goalBuilder = RoleFlowConfig.parse(SAMPLE).get(3);
        assertTrue(goalBuilder.hasOutput());
        assertEquals("goal", goalBuilder.outputKind());
    }

    @Test
    void resolvesLabeledTransitions() {
        Role classifier = RoleFlowConfig.parse(SAMPLE).get(0);
        assertEquals("SignalResponse", classifier.resolve("signal"));
        assertEquals("HandleRequest", classifier.resolve("request"));
        // Unknown decision with no unconditional rule -> null (engine treats as done).
        assertNull(classifier.resolve("nonsense"));
    }

    @Test
    void resolvesDoneAndSelfTransitions() {
        List<Role> roles = RoleFlowConfig.parse(SAMPLE);
        Role signalResponse = roles.get(1);
        Role clarifier = roles.get(2);

        // Unconditional "done".
        assertEquals("done", signalResponse.resolve("anything"));
        assertEquals(List.of("done"), signalResponse.allowedDecisions());

        // Self-transition (clarify) and cancel.
        assertEquals("HandleRequest", clarifier.resolve("unclear"));
        assertEquals("GoalBuilder", clarifier.resolve("clear"));
        assertEquals("done", clarifier.resolve("cancelled"));
        assertEquals(List.of("clear", "unclear", "cancelled"), clarifier.allowedDecisions());
    }

    @Test
    void unconditionalTransitionAdvancesRegardlessOfDecision() {
        Role goalBuilder = RoleFlowConfig.parse(SAMPLE).get(3);
        assertEquals("PlanBuilder", goalBuilder.resolve("continue"));
        assertEquals("PlanBuilder", goalBuilder.resolve("")); // unconditional regardless of decision
        assertEquals("PlanBuilder", goalBuilder.resolve("anything-at-all"));
        assertEquals(List.of("continue"), goalBuilder.allowedDecisions());
    }

    @Test
    void byNameIsCaseInsensitiveAndFirstRoleIsRoleOne() {
        RoleFlowConfig config = new RoleFlowConfig(RoleFlowConfig.parse(SAMPLE));
        assertTrue(config.isActive());
        assertEquals("SignalOrRequest", config.firstRole().name());
        assertEquals("GoalBuilder", config.byName("goalbuilder").name());
        assertNull(config.byName("missing"));
    }

    @Test
    void emptyConfigIsInactive() {
        assertFalse(new RoleFlowConfig(List.of()).isActive());
        assertFalse(new RoleFlowConfig(RoleFlowConfig.parse("# only a comment\n")).isActive());
    }

    @Test
    void shippedActiveConfigParsesIntoSixteenRoles() throws Exception {
        Path path = Path.of("config/roleflow.active");
        RoleFlowConfig config = new RoleFlowConfig(RoleFlowConfig.parse(Files.readString(path)));
        List<Role> roles = config.roles();

        assertEquals(16, roles.size(), "the shipped workflow should define sixteen roles");
        assertEquals(List.of("TopicAnalyzer", "TopicContextBuilder", "SignalOrRequest", "SignalResponse",
                        "HandleRequest", "GoalBuilder", "PlanBuilder", "PlanReviewer", "StepReviewer",
                        "SubgoalPlanner", "ActionPlanner", "InformationPlanner", "DecisionPlanner",
                        "PlanDetailReviewer", "PlanDetailer", "ResponseBuilder"),
                roles.stream().map(Role::name).toList());
        assertEquals("TopicAnalyzer", config.firstRole().name(), "the run starts by analysing topics");

        // StepReviewer calls a function per step category; each planner is a function that returns.
        Role stepReviewer = config.byName("StepReviewer");
        assertTrue(stepReviewer.hasCalls());
        assertEquals("SubgoalPlanner", stepReviewer.functionFor("subgoal"));
        assertEquals("ActionPlanner", stepReviewer.functionFor("action"));
        assertEquals("InformationPlanner", stepReviewer.functionFor("request-for-information"));
        assertEquals("DecisionPlanner", stepReviewer.functionFor("decision-point"));
        // After classifying/detailing steps, StepReviewer hands off to the completeness review.
        assertEquals("PlanDetailReviewer", stepReviewer.resolve("continue"));
        for (String fn : List.of("SubgoalPlanner", "ActionPlanner", "InformationPlanner", "DecisionPlanner")) {
            assertTrue(config.byName(fn).isFunction(), fn + " should be a function");
            assertEquals("return", config.byName(fn).resolve("anything"), fn + " returns to its caller");
        }

        // PlanDetailReviewer provides the TODO list and loops to PlanDetailer when an update is needed.
        Role detailReviewer = config.byName("PlanDetailReviewer");
        assertTrue(detailReviewer.providesTodos());
        assertEquals("PlanDetailer", detailReviewer.resolve("update"));
        assertEquals("ResponseBuilder", detailReviewer.resolve("sufficient"));

        // PlanDetailer is computed, capped at 3 iterations, and calls SubgoalPlanner for each TODO issue.
        Role detailer = config.byName("PlanDetailer");
        assertTrue(detailer.isComputed());
        assertEquals("detail-todos", detailer.compute());
        assertEquals(3, detailer.iterationLimit());
        assertTrue(detailer.hasIterationLimit());
        assertEquals("SubgoalPlanner", detailer.functionFor("ambiguous"));
        assertEquals("SubgoalPlanner", detailer.functionFor("too-high-level"));
        assertEquals("PlanDetailReviewer", detailer.resolve("continue"), "loops back while under the limit");
        assertEquals("ResponseBuilder", detailer.resolve("limit"), "exits to the responder at the limit");

        // TopicAnalyzer provides topics; TopicContextBuilder gathers their context deterministically.
        Role topicAnalyzer = config.byName("TopicAnalyzer");
        assertTrue(topicAnalyzer.providesTopics());
        assertEquals("TopicContextBuilder", topicAnalyzer.resolve("topics"));
        assertEquals("SignalOrRequest", topicAnalyzer.resolve("none"));
        assertEquals("SignalOrRequest", topicAnalyzer.resolve("anything-else"), "defaults forward");
        assertTrue(config.byName("TopicContextBuilder").isComputed());
        assertEquals("build-topic-context", config.byName("TopicContextBuilder").compute());
        assertEquals("SignalOrRequest", config.byName("TopicContextBuilder").resolve("continue"));

        // Goal/plan are the mandatory artifact-producing steps.
        assertEquals("goal", config.byName("GoalBuilder").outputKind());
        assertTrue(config.byName("GoalBuilder").outputMandatory());
        assertEquals("plan", config.byName("PlanBuilder").outputKind());

        // PlanBuilder applies the mathematics skill (gated on the mathematics topic by the engine).
        assertEquals(List.of("mathematics"), config.byName("PlanBuilder").skills());
        assertTrue(config.byName("GoalBuilder").skills().isEmpty(), "GoalBuilder declares no skills");

        // PlanReviewer writes the plan only conditionally; StepReviewer writes nothing.
        Role planReviewer = config.byName("PlanReviewer");
        assertEquals("plan", planReviewer.outputKind());
        assertFalse(planReviewer.outputMandatory(), "the reviewer's output is conditional");
        assertFalse(config.byName("StepReviewer").hasOutput());

        // StepReviewer is computed deterministically by the engine; ResponseBuilder only reports locations.
        assertTrue(config.byName("StepReviewer").isComputed(), "the step reviewer is engine-computed");
        assertEquals("classify-steps", config.byName("StepReviewer").compute());

        // PlanReviewer fetches web context to sanity-check the plan against the topic.
        assertTrue(config.byName("PlanReviewer").researchesTopic(), "the plan reviewer researches the topic");
        assertFalse(config.byName("GoalBuilder").researchesTopic());
        assertFalse(config.byName("ResponseBuilder").needsArtifactContent(),
                "the responder only reports file locations");

        // The plan flows through both reviewers before the response.
        assertEquals("PlanReviewer", config.byName("PlanBuilder").resolve("continue"));
        assertEquals("PlanReviewer", planReviewer.resolve("change"), "a change re-reviews the plan");
        assertEquals("StepReviewer", planReviewer.resolve("ok"));
        // Any unexpected decision (e.g. a model that says "continue") defaults forward, not to "done".
        assertEquals("StepReviewer", planReviewer.resolve("continue"));
        assertEquals("StepReviewer", planReviewer.resolve("anything-unexpected"));
        assertEquals("PlanDetailReviewer", config.byName("StepReviewer").resolve("continue"));
    }

    @Test
    void verificationAndNextStepsPromptsDistinguishChangeableFromInformationOnlyState() throws Exception {
        RoleFlowConfig config = new RoleFlowConfig(
                RoleFlowConfig.parse(Files.readString(Path.of("config/roleflow.active"))));

        // PlanBuilder's prompt teaches the changeable vs information-only distinction for Phase 3 and Phase 4.
        String planBuilder = config.byName("PlanBuilder").action().toLowerCase();
        assertTrue(planBuilder.contains("information-only"), "PlanBuilder must explain information-only state");
        assertTrue(planBuilder.contains("changeable"), "PlanBuilder must explain changeable state");
        assertTrue(planBuilder.contains("verification") && planBuilder.contains("next steps"));

        // PlanReviewer flags steps that try to change an information-only result.
        String planReviewer = config.byName("PlanReviewer").action().toLowerCase();
        assertTrue(planReviewer.contains("information-only"), "PlanReviewer must flag information-only changes");
    }

    @Test
    void clarifierUsesTheAwaitSentinelToWaitForTheUser() throws Exception {
        RoleFlowConfig config = new RoleFlowConfig(
                RoleFlowConfig.parse(Files.readString(Path.of("config/roleflow.active"))));
        Role handleRequest = config.byName("HandleRequest");

        assertEquals("await", handleRequest.resolve("unclear"));
        assertEquals("GoalBuilder", handleRequest.resolve("clear"));
        assertEquals("done", handleRequest.resolve("cancelled"));
    }

    @Test
    void parsesConditionalOutput() {
        List<Role> roles = RoleFlowConfig.parse(
                "1. Reviewer\nRole: r\nAction: review\nOutput: plan conditional\nTransition: done\n");

        assertEquals("plan", roles.get(0).outputKind());
        assertFalse(roles.get(0).outputMandatory());
    }

    @Test
    void parsesReadsFieldForNonOutputReaders() {
        List<Role> roles = RoleFlowConfig.parse(
                "1. Reviewer\nRole: r\nAction: classify the steps\nOutput: none\nReads: plan\n"
                        + "Transition: done\n");

        assertFalse(roles.get(0).hasOutput());
        assertTrue(roles.get(0).needsArtifactContent(), "a Reads field opts a non-output role into content");
        assertTrue(roles.get(0).action().contains("classify the steps"),
                "the Reads field must not swallow the Action text");
    }
}
