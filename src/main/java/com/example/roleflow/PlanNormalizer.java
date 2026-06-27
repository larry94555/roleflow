package com.example.roleflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coerces a model-produced plan into the canonical four-phase structure that {@link PlanValidator} requires.
 *
 * <p>Smaller models reliably organise a plan under "Phase 1..4" but vary the heading style (e.g.
 * {@code # Phase 1} instead of {@code ## Phase 1 - Preparation}) and express steps as markdown sub-headings
 * ({@code ## Assumption: ...}) rather than {@code - } bullets. This normaliser rewrites whatever the model
 * emits into the fixed format, so the deterministic structure is produced in code rather than hoped for from
 * the model. It is applied only when a plan fails validation, so an already-canonical plan is left untouched.
 */
final class PlanNormalizer {

    private PlanNormalizer() {}

    /** The canonical header for each phase, in order. */
    private static final String[] CANONICAL = {
            "## Phase 1 - Preparation",
            "## Phase 2 - Action",
            "## Phase 3 - Verification",
            "## Phase 4 - Next steps"
    };

    /** A line that introduces a phase, however the model styled it (e.g. "# Phase 1", "Phase 2:"). */
    private static final Pattern PHASE_HEADER = Pattern.compile("(?i)^\\s*#*\\s*phase\\s*([1-4])\\b.*$");

    /**
     * Rewrites {@code plan} into the four canonical phase sections, turning each content line into a
     * {@code - } step. If the text has no recognisable phase markers it is returned unchanged, so
     * non-plan or unexpected text is never mangled.
     */
    static String normalize(String plan) {
        if (plan == null || plan.isBlank()) {
            return plan == null ? "" : plan;
        }
        List<List<String>> steps = new ArrayList<>();
        for (int i = 0; i < CANONICAL.length; i++) {
            steps.add(new ArrayList<>());
        }
        int current = -1;
        boolean sawPhase = false;
        for (String line : plan.split("\\R")) {
            Matcher header = PHASE_HEADER.matcher(line);
            if (header.matches()) {
                current = Integer.parseInt(header.group(1)) - 1;
                sawPhase = true;
                continue;
            }
            if (current < 0) {
                continue; // preamble before the first phase header (e.g. a restated goal) is dropped
            }
            String step = toStep(line);
            if (!step.isBlank()) {
                steps.get(current).add(step);
            }
        }
        if (!sawPhase) {
            return plan; // not a phase-structured plan; leave it for the validator to reject
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < CANONICAL.length; i++) {
            if (i > 0) out.append('\n');
            out.append(CANONICAL[i]).append('\n');
            for (String step : steps.get(i)) {
                out.append("- ").append(step).append('\n');
            }
        }
        return out.toString().strip();
    }

    /** Strips a leading markdown heading, bullet, or number marker, returning the bare step text. */
    private static String toStep(String line) {
        String text = line.strip();
        if (text.isEmpty()) {
            return "";
        }
        return text.replaceFirst("^#+\\s*", "")               // a markdown heading marker ("##")
                .replaceFirst("^(?:[-*]|\\d+[.)])\\s*", "")    // a bullet or numbered-list marker
                .strip();
    }
}
