package com.example.roleflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDocumentTest {

    private static final String PLAN = "## Phase 1 - Preparation\n- Assumption: x\n- Decision: y\n"
            + "## Phase 2 - Action\n- act\n## Phase 3 - Verification\n- verify\n## Phase 4 - Next steps\n- next";

    @Test
    void putsTheGoalSectionFirstThenThePlanSection() {
        String doc = PlanDocument.compose("The goal body.", PLAN);

        assertTrue(doc.startsWith("# Goal\n\nThe goal body."), doc);
        int goalAt = doc.indexOf("# Goal");
        int planAt = doc.indexOf("# Plan");
        assertTrue(goalAt >= 0 && planAt > goalAt, "goal section must come before the plan section");
        // The plan's phase headers are preserved under the Plan section.
        assertTrue(doc.contains("# Plan\n\n## Phase 1 - Preparation"), doc);
        assertTrue(PlanValidator.isValid(doc), "the combined document still contains a valid plan");
    }

    @Test
    void stripsADuplicateLeadingGoalHeadingFromTheGoalBody() {
        String doc = PlanDocument.compose("# Goal\nDeliver a report", PLAN);

        // Only one "# Goal" heading, immediately followed by the body (no doubled heading).
        assertTrue(doc.startsWith("# Goal\n\nDeliver a report"), doc);
        assertEquals(1, countOccurrences(doc, "# Goal"));
    }

    @Test
    void stripsALeadingPlanHeadingFromThePlanBody() {
        String doc = PlanDocument.compose("g", "# Plan\n" + PLAN);

        assertEquals(1, countOccurrences(doc, "# Plan"));
        assertTrue(doc.contains("# Plan\n\n## Phase 1 - Preparation"), doc);
    }

    @Test
    void doesNotRepeatTheGoalInsideThePlanSection() {
        // The model restated the goal (and a stray "Plan" heading) before the phases; the composed document
        // must drop that preamble so the goal appears only once, in the Goal section.
        String planWithRestatedGoal = "Goal: Build the thing\nPlan\n" + PLAN;

        String doc = PlanDocument.compose("Build the thing", planWithRestatedGoal);

        // The plan section begins directly at Phase 1 — no restated goal, no stray "Plan" line before it.
        assertTrue(doc.contains("# Plan\n\n## Phase 1 - Preparation"), doc);
        assertFalse(doc.contains("Goal: Build the thing"), "the goal must not be restated inside the plan");
        // "Build the thing" appears once (in the Goal section only).
        assertEquals(1, countOccurrences(doc, "Build the thing"));
    }

    @Test
    void handlesABlankGoalGracefully() {
        String doc = PlanDocument.compose("", PLAN);

        assertTrue(doc.startsWith("# Goal\n\n# Plan"), doc);
        assertFalse(doc.contains("# Goal\n\n\n"), "no stray blank goal body");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
