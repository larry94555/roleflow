package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepClassifierTest {

    @Test
    void classifiesBulletStepsByKeyword() {
        String plan =
                "## Phase 1 - Preparation\n- gather background information\n"
                + "## Phase 2 - Action\n- write the script\n"
                + "## Phase 3 - Verification\n- run the tests\n"
                + "## Phase 4 - Next steps\n- design a follow-up subgoal";

        List<StepClassifier.Classified> steps = StepClassifier.classify(plan);

        assertEquals(4, steps.size());
        assertEquals(StepClassifier.REQUEST_FOR_INFORMATION, steps.get(0).classification());
        assertEquals(StepClassifier.ACTION, steps.get(1).classification());
        assertEquals(StepClassifier.ACTION, steps.get(2).classification());
        assertEquals(StepClassifier.SUBGOAL, steps.get(3).classification());
        assertEquals("gather background information", steps.get(0).text());
    }

    @Test
    void classifiesAssumptionsAndChoicesAsDecisionPoints() {
        String plan =
                "## Phase 1 - Preparation\n"
                + "- Assumption: Python is available\n"
                + "- Decision: use Python as the language\n"
                + "- Figure out which database to use\n"
                + "- choose a web framework\n"
                + "## Phase 2 - Action\n- write the code\n"
                + "## Phase 3 - Verification\n- review results\n"
                + "## Phase 4 - Next steps\n- follow up";

        List<StepClassifier.Classified> steps = StepClassifier.classify(plan);

        assertEquals(StepClassifier.DECISION_POINT, steps.get(0).classification(), "an assumption");
        assertEquals(StepClassifier.DECISION_POINT, steps.get(1).classification(), "a decision");
        assertEquals(StepClassifier.DECISION_POINT, steps.get(2).classification(), "a deferred decision");
        assertEquals(StepClassifier.DECISION_POINT, steps.get(3).classification(), "a choice");
        assertEquals(StepClassifier.ACTION, steps.get(4).classification());
    }

    @Test
    void decisionWordsTakePriorityOverActionWords() {
        // "Decide ... and install ..." mentions an action, but choosing is the point of the step.
        List<StepClassifier.Classified> steps =
                StepClassifier.classify("- Decide which database to use and install it");

        assertEquals(StepClassifier.DECISION_POINT, steps.get(0).classification());
    }

    @Test
    void stripsAPhaseLabelFromTheStepText() {
        List<StepClassifier.Classified> steps = StepClassifier.classify(
                "- Phase 1: Ensure Python is installed by running python --version");

        assertEquals(1, steps.size());
        assertEquals("Ensure Python is installed by running python --version", steps.get(0).text());
        assertEquals(StepClassifier.ACTION, steps.get(0).classification(), "installing/running is an action");
    }

    @Test
    void handlesNumberedSteps() {
        List<StepClassifier.Classified> steps = StepClassifier.classify("1. research the topic\n2. build it");

        assertEquals(2, steps.size());
        assertEquals(StepClassifier.REQUEST_FOR_INFORMATION, steps.get(0).classification());
        assertEquals(StepClassifier.ACTION, steps.get(1).classification());
    }

    @Test
    void returnsNoStepsForAPlanWithoutBullets() {
        assertTrue(StepClassifier.classify("# Goal\nsome prose with no bullet points").isEmpty());
        assertTrue(StepClassifier.classify(null).isEmpty());
    }

    @Test
    void defaultsAmbiguousStepsToAction() {
        List<StepClassifier.Classified> steps = StepClassifier.classify("- something unspecified happens");

        assertEquals(StepClassifier.ACTION, steps.get(0).classification());
    }
}
