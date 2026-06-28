package com.example.roleflow;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composes the single Markdown document that the workflow writes to {@code goals/}: the goal first, then the
 * plan. RoleFlow used to write a separate goal file and plan file; both now live in one {@code plan_*.md}
 * file, with a {@code # Goal} section followed by a {@code # Plan} section, so the goals directory holds one
 * file per request instead of two.
 */
final class PlanDocument {

    private PlanDocument() {}

    static final String GOAL_HEADING = "# Goal";
    static final String PLAN_HEADING = "# Plan";
    static final String STEP_DETAILS_HEADING = "# Step Details";

    /** The plan's first phase header, however the model styled it (e.g. "# Phase 1", "## Phase 1 - ..."). */
    private static final Pattern FIRST_PHASE = Pattern.compile("(?im)^\\s*#*\\s*phase\\s*1\\b.*$");

    /**
     * Builds the combined document from the goal and plan bodies. The goal becomes the {@code # Goal} section
     * and the plan becomes the {@code # Plan} section. The plan body is trimmed to start at its first phase
     * header, so any preamble the model put before the phases — a restated goal, a stray "Plan" heading — is
     * dropped and the goal is not stated twice. The four-phase {@code ## Phase ...} headers nest under
     * {@code # Plan}.
     */
    static String compose(String goal, String plan) {
        return compose(goal, plan, List.of());
    }

    /**
     * As {@link #compose(String, String)}, additionally appending a {@code # Step Details} section made of the
     * given per-step detail sections (each a {@code ## Step N: ...} subsection produced by a function). When
     * there are no step sections, the document is identical to the two-argument form.
     */
    static String compose(String goal, String plan, List<String> stepSections) {
        String goalBody = stripLeadingHeading(goal == null ? "" : goal.strip(), "goal");
        String planBody = planSection(plan == null ? "" : plan.strip());

        StringBuilder document = new StringBuilder(GOAL_HEADING).append("\n\n");
        if (!goalBody.isBlank()) {
            document.append(goalBody).append("\n\n");
        }
        document.append(PLAN_HEADING).append("\n\n").append(planBody);
        if (stepSections != null && !stepSections.isEmpty()) {
            document.append("\n\n").append(STEP_DETAILS_HEADING);
            for (String section : stepSections) {
                if (section != null && !section.isBlank()) {
                    document.append("\n\n").append(section.strip());
                }
            }
        }
        return document.toString().strip();
    }

    /**
     * Returns the plan body starting at its first phase header, dropping any preamble before it (a restated
     * goal, a "# Plan" heading, etc.) so the goal is never repeated inside the plan section. If the plan has
     * no recognizable phase header, falls back to dropping just a leading "# Plan" heading.
     */
    private static String planSection(String plan) {
        if (plan.isBlank()) {
            return plan;
        }
        Matcher firstPhase = FIRST_PHASE.matcher(plan);
        if (firstPhase.find()) {
            return plan.substring(firstPhase.start()).strip();
        }
        return stripLeadingHeading(plan, "plan");
    }

    /** Drops a leading Markdown heading line whose text is exactly the given word (e.g. "# Goal", "## Plan"). */
    private static String stripLeadingHeading(String text, String word) {
        if (text.isBlank()) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        String firstLine = (firstBreak < 0 ? text : text.substring(0, firstBreak)).strip();
        if (firstLine.matches("(?i)#+\\s*" + word + "\\s*:?\\s*")) {
            return firstBreak < 0 ? "" : text.substring(firstBreak + 1).strip();
        }
        return text;
    }
}
