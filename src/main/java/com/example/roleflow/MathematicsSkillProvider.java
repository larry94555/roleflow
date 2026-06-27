package com.example.roleflow;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Provides the {@code mathematics} skill: how to reason about a mathematical investigation, in particular
 * that searching a fixed range for a counterexample is a complete result whether or not one is found, and
 * that the goal is to GAIN INFORMATION about a conjecture rather than to RESOLVE it.
 */
@Component
public class MathematicsSkillProvider implements SkillProvider {

    private static final String INSTRUCTIONS = """
            When the prompt involves mathematics, reason about it the way mathematics actually works:

            - A conjecture asserts that every element of some set (often the integers, but it depends on the
              topic) has a certain property. A program investigates it by checking elements for that property.
            - The purpose of such an investigation is to GAIN INFORMATION about the conjecture, NOT to RESOLVE
              it. Resolving a conjecture means a formal PROOF that it is true, false, or undecidable; that is a
              separate, far harder undertaking and is almost never the goal of a search program.
            - There are exactly two ways a search can complete, and BOTH are successful, COMPLETE results:
                1. It finds a single counterexample — an element lacking the property. This DISPROVES the
                   conjecture.
                2. It checks every element in the chosen range and finds no counterexample. This does NOT prove
                   the conjecture; it is evidence that the conjecture holds over that range.
            - Finding no counterexample is a normal, expected, COMPLETE outcome. It is NOT a failure and does
              NOT call for "retrying", "extending the search", or "exploring other methods" as if something
              went wrong.
            - Therefore a plan to search a fixed range (e.g. the first 10,000 integers) is DONE once the range
              has been checked. Its result is the information gained: either a counterexample, or confirmation
              that none exists in that range. Do not add steps that treat a clean search as an unsolved problem.
            """;

    @Override
    public List<Skill> skills() {
        return List.of(new Skill(
                "mathematics",
                "How to reason about a mathematical investigation (a range search is complete whether or not "
                        + "a counterexample is found; the goal is information, not a proof).",
                INSTRUCTIONS.strip()));
    }
}
