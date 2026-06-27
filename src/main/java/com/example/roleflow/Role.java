package com.example.roleflow;

import java.util.List;

/**
 * One step in the RoleFlow workflow defined by {@code roleflow.active}. A role is, for all practical
 * purposes, a system prompt ({@link #action}) plus the rules for where to go next ({@link #transitions}).
 *
 * @param number          the role's position number in the config
 * @param name            the role's name (transition targets reference this)
 * @param title           a short human label (e.g. "Classifier")
 * @param action          the system-prompt instructions for this step
 * @param outputKind      the artifact this step writes to {@code goals/} ("goal", "plan"), or null for none
 * @param outputMandatory when an output role returns an empty artifact, fall back to its message (true) or
 *                        write nothing (false). A reviewer that only sometimes rewrites a file is not
 *                        mandatory, so a "no change" reply leaves the existing file untouched.
 * @param readsArtifacts  whether this role needs the full content of the artifacts created so far in its
 *                        system prompt. Output roles always get it; a non-output role (e.g. a reviewer
 *                        that analyses the plan) sets this to opt in, while a pure reporting role does not.
 * @param compute         the name of an engine built-in that produces this role's result deterministically
 *                        instead of calling the model (e.g. {@code "classify-steps"}), or null for a normal
 *                        model-driven role.
 * @param researchesTopic whether the engine should fetch general web context about the request's topic and
 *                        include it in this role's system prompt (e.g. so a reviewer can sanity-check a
 *                        plan against how the topic actually works).
 * @param transitions     ordered decision-to-target rules
 */
public record Role(int number, String name, String title, String action, String outputKind,
                   boolean outputMandatory, boolean readsArtifacts, String compute,
                   boolean researchesTopic, List<Transition> transitions) {

    /** True when this role is computed deterministically by the engine rather than by the model. */
    public boolean isComputed() {
        return compute != null && !compute.isBlank();
    }

    /** Sentinel target meaning "the run is complete". */
    public static final String DONE = "done";

    /** Sentinel target meaning "return this role's message to the user and wait for their next prompt". */
    public static final String AWAIT = "await";

    /**
     * A transition rule. A null {@link #label} is an unconditional transition (taken regardless of the
     * model's decision); otherwise the rule fires when the decision matches the label.
     */
    public record Transition(String label, String target) {}

    public boolean hasOutput() {
        return outputKind != null && !outputKind.isBlank() && !"none".equalsIgnoreCase(outputKind);
    }

    /** True when this role should see the full content of prior artifacts, not just their locations. */
    public boolean needsArtifactContent() {
        return hasOutput() || readsArtifacts;
    }

    /**
     * Resolves the model's decision to a transition target: a role name, {@link #DONE}, or null when no
     * rule applies (which the engine also treats as completion).
     */
    public String resolve(String decision) {
        String value = decision == null ? "" : decision.trim();
        Transition unconditional = null;
        for (Transition transition : transitions) {
            if (transition.label() == null) {
                unconditional = transition;
            } else if (transition.label().equalsIgnoreCase(value)) {
                return transition.target();
            }
        }
        return unconditional == null ? null : unconditional.target();
    }

    /** The decision values the model is allowed to return, for inclusion in the system prompt. */
    public List<String> allowedDecisions() {
        List<String> labels = transitions.stream()
                .map(Transition::label)
                .filter(label -> label != null)
                .toList();
        if (!labels.isEmpty()) return labels;
        // Unconditional transition: the model just needs to signal completion or continuation.
        if (!transitions.isEmpty() && DONE.equalsIgnoreCase(transitions.get(0).target())) {
            return List.of(DONE);
        }
        return List.of("continue");
    }
}
