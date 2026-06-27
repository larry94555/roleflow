package com.example.roleflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanNormalizerTest {

    @Test
    void coercesLoosePhaseHeadingsAndSubHeadingStepsIntoTheCanonicalFormat() {
        // The exact shape a 3B model produced for the Legendre prompt: "# Phase 1" headers and
        // "## Assumption: ..." markdown sub-headings instead of "## Phase 1 - Preparation" and "- " bullets.
        String loose = "# Phase 1\n## Assumption: A language is needed.\n## Decision: Use Python.\n\n"
                + "# Phase 2\n## Action: Install Python.\n\n"
                + "# Phase 3\n## Confirmation: Run it.\n\n"
                + "# Phase 4\n## Follow-Up: Document results.";

        String normalized = PlanNormalizer.normalize(loose);

        assertTrue(PlanValidator.isValid(normalized), () -> "should be valid but was:\n" + normalized);
        assertTrue(normalized.contains("## Phase 1 - Preparation"));
        assertTrue(normalized.contains("- Assumption: A language is needed."));
        assertTrue(normalized.contains("- Decision: Use Python."));
        assertTrue(normalized.contains("## Phase 4 - Next steps"));
        // The original loose markers must be gone.
        assertFalse(normalized.contains("# Phase 1\n"));
        assertFalse(normalized.contains("## Assumption:"));
    }

    @Test
    void dropsPreambleBeforeTheFirstPhase() {
        String withPreamble = "The plan is outlined below.\nHere it is:\n"
                + "# Phase 1\n- Assumption: x\n- Decision: y\n"
                + "# Phase 2\n- act\n# Phase 3\n- verify\n# Phase 4\n- next";

        String normalized = PlanNormalizer.normalize(withPreamble);

        assertTrue(PlanValidator.isValid(normalized));
        assertFalse(normalized.contains("outlined below"), "preamble must be dropped");
        assertTrue(normalized.startsWith("## Phase 1 - Preparation"));
    }

    @Test
    void leavesAnAlreadyCanonicalPlanValid() {
        String canonical = "## Phase 1 - Preparation\n- Assumption: ready\n- Decision: use Python\n"
                + "## Phase 2 - Action\n- do work\n## Phase 3 - Verification\n- verify\n"
                + "## Phase 4 - Next steps\n- follow up";

        String normalized = PlanNormalizer.normalize(canonical);

        assertTrue(PlanValidator.isValid(normalized));
        assertTrue(normalized.contains("- Assumption: ready"));
        assertTrue(normalized.contains("- Decision: use Python"));
    }

    @Test
    void returnsTextUnchangedWhenItHasNoPhaseMarkers() {
        String prose = "This is just a paragraph with no phases at all.";
        assertEquals(prose, PlanNormalizer.normalize(prose));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("", PlanNormalizer.normalize(null));
        assertEquals("   ", PlanNormalizer.normalize("   "));
    }
}
