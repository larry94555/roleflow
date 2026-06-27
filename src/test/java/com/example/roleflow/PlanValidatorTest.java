package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanValidatorTest {

    private static final String VALID =
            "## Phase 1 - Preparation\n- Assumption: tools are available\n- Decision: use Python\n- gather info\n"
            + "## Phase 2 - Action\n- do the work\n"
            + "## Phase 3 - Verification\n- check the result\n"
            + "## Phase 4 - Next steps\n- follow up";

    @Test
    void acceptsAWellFormedFourPhasePlan() {
        assertTrue(PlanValidator.isValid(VALID));
        assertTrue(PlanValidator.problems(VALID).isEmpty());
    }

    @Test
    void acceptsNumberedSteps() {
        String numbered =
                "## Phase 1 - Preparation\n1. Assumption: x\n2. Decision: use Python\n3. gather\n"
                + "## Phase 2 - Action\n1. do\n"
                + "## Phase 3 - Verification\n1. check\n## Phase 4 - Next steps\n1. follow up";
        assertTrue(PlanValidator.isValid(numbered));
    }

    @Test
    void rejectsAPlanWhosePreparationOmitsAssumptions() {
        String noAssumptions =
                "## Phase 1 - Preparation\n- Decision: use Python\n- gather\n## Phase 2 - Action\n- do\n"
                + "## Phase 3 - Verification\n- check\n## Phase 4 - Next steps\n- follow up";

        assertTrue(PlanValidator.problems(noAssumptions).stream()
                .anyMatch(p -> p.contains("call out the assumptions")));
    }

    @Test
    void rejectsAPlanWhosePreparationOmitsDecisionPoints() {
        String noDecisions =
                "## Phase 1 - Preparation\n- Assumption: tools exist\n- gather\n## Phase 2 - Action\n- do\n"
                + "## Phase 3 - Verification\n- check\n## Phase 4 - Next steps\n- follow up";

        assertTrue(PlanValidator.problems(noDecisions).stream()
                .anyMatch(p -> p.contains("call out the decision points")));
    }

    @Test
    void rejectsAPlanThatRestatesTheGoalWithNoPhases() {
        List<String> problems = PlanValidator.problems("# Goal\n- run through integers\n- search");

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Phase 1 - Preparation")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("Phase 4 - Next steps")));
    }

    @Test
    void rejectsAMissingPhase() {
        String noPhase3 =
                "## Phase 1 - Preparation\n- a\n## Phase 2 - Action\n- b\n## Phase 4 - Next steps\n- d";

        List<String> problems = PlanValidator.problems(noPhase3);
        assertTrue(problems.stream().anyMatch(p -> p.contains("Phase 3 - Verification")));
    }

    @Test
    void rejectsAPhaseWithNoSteps() {
        String noSteps =
                "## Phase 1 - Preparation\nthis paragraph has no bullet\n"
                + "## Phase 2 - Action\n- b\n## Phase 3 - Verification\n- c\n## Phase 4 - Next steps\n- d";

        List<String> problems = PlanValidator.problems(noSteps);
        assertTrue(problems.stream().anyMatch(p -> p.contains("has no steps")
                && p.contains("Phase 1 - Preparation")));
    }

    @Test
    void rejectsPhasesOutOfOrder() {
        // Otherwise valid (Phase 1 has assumptions/decisions) so the only problem is the ordering.
        String reordered =
                "## Phase 2 - Action\n- b\n"
                + "## Phase 1 - Preparation\n- Assumption: x\n- Decision: y\n- a\n"
                + "## Phase 3 - Verification\n- c\n## Phase 4 - Next steps\n- d";

        assertTrue(PlanValidator.problems(reordered).stream().anyMatch(p -> p.contains("not in order")));
    }

    @Test
    void rejectsAnEmptyPlan() {
        assertFalse(PlanValidator.problems("").isEmpty());
        assertFalse(PlanValidator.problems(null).isEmpty());
    }
}
