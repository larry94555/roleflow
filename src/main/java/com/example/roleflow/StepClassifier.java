package com.example.roleflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically classifies the steps of a plan as {@code decision-point}, {@code request-for-information},
 * {@code action}, or {@code subgoal}. The categories are fixed, so the engine performs this in code rather
 * than relying on the model. Classification is a transparent keyword heuristic (see {@link #classify}); it
 * favors a useful, repeatable label over perfect judgment.
 */
final class StepClassifier {

    private StepClassifier() {}

    /** An assumption to confirm or a choice that needs to be made. */
    static final String DECISION_POINT = "decision-point";
    static final String REQUEST_FOR_INFORMATION = "request-for-information";
    static final String ACTION = "action";
    static final String SUBGOAL = "subgoal";

    /** A plan step and the category it was classified into. */
    record Classified(String text, String classification) {}

    /** A bullet ("- "/"* ") or numbered ("1. ") line, capturing the step text. */
    private static final Pattern STEP_LINE = Pattern.compile("(?m)^\\s*(?:[-*]|\\d+\\.)\\s+(.*\\S)\\s*$");
    /** A leading "Phase 2:" / "Phase 2 -" label to drop from a step's text for display. */
    private static final Pattern PHASE_LABEL = Pattern.compile("(?i)^phase\\s*\\d+\\s*[-:]\\s*");

    private static final List<String> DECISION_WORDS = List.of(
            "assumption", "assume", "decision", "decide", "choose", "choice", "select", "pick",
            "figure out", "decision point");
    private static final List<String> SUBGOAL_WORDS = List.of(
            "subgoal", "sub-goal", "break down", "breakdown", "design", "architect", "sub-plan");
    private static final List<String> ACTION_WORDS = List.of(
            "write", "run", "execute", "install", "create", "build", "implement", "process", "generate",
            "document", "configure", "deploy", "send", "update", "delete", "develop", "make", "produce",
            "compute", "calculate", "iterate", "output", "set up", "perform", "do ");
    private static final List<String> INFO_WORDS = List.of(
            "gather", "research", "find", "look up", "confirm", "check", "verify", "review", "understand",
            "determine", "investigate", "read", "study", "identify", "collect", "ensure", "assess",
            "evaluate", "request");

    /** Extracts the plan's steps (bullet/numbered lines) and classifies each. */
    static List<Classified> classify(String plan) {
        List<Classified> classified = new ArrayList<>();
        if (plan == null) return classified;
        Matcher matcher = STEP_LINE.matcher(plan);
        while (matcher.find()) {
            String text = PHASE_LABEL.matcher(matcher.group(1).trim()).replaceFirst("").trim();
            classified.add(new Classified(text, classify(text, classifyKey(matcher.group(1)))));
        }
        return classified;
    }

    private static String classifyKey(String raw) {
        return raw.toLowerCase(Locale.ROOT);
    }

    private static String classify(String displayText, String lower) {
        // An assumption or a choice to be made takes priority — it is a decision point regardless of any
        // action/info words it also mentions (e.g. "Decide which database to use and install it").
        if (containsAny(lower, DECISION_WORDS)) return DECISION_POINT;
        if (containsAny(lower, SUBGOAL_WORDS)) return SUBGOAL;
        if (containsAny(lower, ACTION_WORDS)) return ACTION;
        if (containsAny(lower, INFO_WORDS)) return REQUEST_FOR_INFORMATION;
        return ACTION; // most steps "do" something; default to action
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
