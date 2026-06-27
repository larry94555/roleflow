package com.example.roleflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic check that a generated plan has the fixed four-phase structure that PlanBuilder is
 * required to produce. The format is fixed, so the engine enforces it in code rather than relying on the
 * model: a plan must contain, in order, the headers
 * <em>Phase 1 - Preparation</em>, <em>Phase 2 - Action</em>, <em>Phase 3 - Verification</em>, and
 * <em>Phase 4 - Next steps</em>, each followed by at least one bullet/numbered step.
 *
 * <p>{@link #problems(String)} returns a list of human-readable problems (empty means the plan is valid),
 * which the engine surfaces to the model as corrective feedback and records in the audit trail.
 */
final class PlanValidator {

    private PlanValidator() {}

    /** A required phase: its display label and a pattern matching its header line. */
    private record Phase(String label, Pattern header) {}

    private static final List<Phase> PHASES = List.of(
            new Phase("Phase 1 - Preparation", Pattern.compile("(?im)^.*\\bphase\\s*1\\b.*preparation.*$")),
            new Phase("Phase 2 - Action", Pattern.compile("(?im)^.*\\bphase\\s*2\\b.*action.*$")),
            new Phase("Phase 3 - Verification", Pattern.compile("(?im)^.*\\bphase\\s*3\\b.*verification.*$")),
            new Phase("Phase 4 - Next steps", Pattern.compile("(?im)^.*\\bphase\\s*4\\b.*next.*$")));

    /** A bullet ("- "/"* ") or numbered ("1. ") step line. */
    private static final Pattern STEP = Pattern.compile("(?m)^\\s*(?:[-*]|\\d+\\.)\\s+\\S");

    /** Phase 1 must address the request's assumptions and decision points. */
    private static final Pattern ASSUMPTIONS = Pattern.compile("(?i)assum");          // assume, assumption(s)
    private static final Pattern DECISIONS = Pattern.compile("(?i)decid|decision");   // decide, decision(s)

    /** Returns the structural problems with {@code plan}; an empty list means it is valid. */
    static List<String> problems(String plan) {
        if (plan == null || plan.isBlank()) {
            return List.of("the plan is empty");
        }
        List<String> problems = new ArrayList<>();
        int[] start = new int[PHASES.size()];
        int[] end = new int[PHASES.size()];
        for (int i = 0; i < PHASES.size(); i++) {
            Matcher matcher = PHASES.get(i).header().matcher(plan);
            if (matcher.find()) {
                start[i] = matcher.start();
                end[i] = matcher.end();
            } else {
                start[i] = -1;
                problems.add("missing the '" + PHASES.get(i).label() + "' header");
            }
        }

        // Each found phase must contain at least one step; Phase 1 must also call out assumptions and
        // decision points (so e.g. the programming language for an application is never silently ignored).
        for (int i = 0; i < PHASES.size(); i++) {
            if (start[i] < 0) continue;
            int sectionEnd = plan.length();
            for (int j = 0; j < PHASES.size(); j++) {
                if (start[j] > end[i] && start[j] < sectionEnd) sectionEnd = start[j];
            }
            String section = plan.substring(end[i], sectionEnd);
            if (!STEP.matcher(section).find()) {
                problems.add("the '" + PHASES.get(i).label() + "' phase has no steps");
            }
            if (i == 0) {
                if (!ASSUMPTIONS.matcher(section).find()) {
                    problems.add("Phase 1 - Preparation must call out the assumptions "
                            + "(state them as 'Assumption: ...', or add a step to figure out the assumptions)");
                }
                if (!DECISIONS.matcher(section).find()) {
                    problems.add("Phase 1 - Preparation must call out the decision points "
                            + "(make them as 'Decision: ...', or add a step to figure out and make the decisions)");
                }
            }
        }

        // The four phases must appear in order.
        if (problems.isEmpty()) {
            for (int i = 1; i < PHASES.size(); i++) {
                if (start[i] <= start[i - 1]) {
                    problems.add("the four phases are not in order");
                    break;
                }
            }
        }
        return problems;
    }

    static boolean isValid(String plan) {
        return problems(plan).isEmpty();
    }
}
