package com.example.roleflow;

/**
 * One entry in an audit trail. A trail is the ordered sequence of events produced while a single user
 * prompt is processed through the role workflow. Different event {@link Type}s populate different fields;
 * unused fields are null. The {@code timestamp} is an ISO-8601 string so it serializes cleanly to the
 * audit web page and reads well in the log file.
 */
public record AuditEvent(
        long seq,
        String timestamp,
        Type type,
        String role,
        Integer iteration,
        String systemPrompt,
        String userPrompt,
        String response,
        String decision,
        String transition,
        String detail) {

    public enum Type {
        /** The run for a prompt began. */
        RUN_STARTED,
        /** A role started executing (with its iteration count within the run). */
        ROLE_STARTED,
        /** The system prompt and user prompt sent to the model for a role. */
        MODEL_REQUEST,
        /** The raw response the model returned, plus the decision it chose. */
        MODEL_RESPONSE,
        /** A goal/plan artifact file was written. */
        ARTIFACT_WRITTEN,
        /** How the model's decision was turned into the next step. */
        TRANSITION,
        /** A role asked the user a clarifying question; the run pauses until the user replies. */
        CLARIFICATION_PAUSE,
        /** The run finished. */
        RUN_COMPLETED,
        /** A model call failed. */
        MODEL_ERROR
    }
}
