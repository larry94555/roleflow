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
    void shippedActiveConfigParsesIntoSixRoles() throws Exception {
        Path path = Path.of("config/roleflow.active");
        List<Role> roles = RoleFlowConfig.parse(Files.readString(path));

        assertEquals(6, roles.size(), "the shipped workflow should define six roles");
        assertEquals(List.of("SignalOrRequest", "SignalResponse", "HandleRequest",
                        "GoalBuilder", "PlanBuilder", "ResponseBuilder"),
                roles.stream().map(Role::name).toList());
        // Goal/plan are the artifact-producing steps.
        assertEquals("goal", roles.get(3).outputKind());
        assertEquals("plan", roles.get(4).outputKind());
    }
}
