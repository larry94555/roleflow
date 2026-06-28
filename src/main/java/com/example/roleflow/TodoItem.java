package com.example.roleflow;

/**
 * One entry in the PlanDetailReviewer's TODO list: a plan step that is not yet detailed enough, together with
 * the kind of {@link #issue} it has. The issue drives the extra instruction PlanDetailer passes to
 * SubgoalPlanner: an {@code "ambiguous"} step needs decision points and assumptions; a
 * {@code "too-high-level"} step needs a breakdown into substeps.
 *
 * @param step  the text of the plan step that needs more detail
 * @param issue {@code "ambiguous"} or {@code "too-high-level"}
 */
public record TodoItem(String step, String issue) {

    public static final String AMBIGUOUS = "ambiguous";
    public static final String TOO_HIGH_LEVEL = "too-high-level";
}
