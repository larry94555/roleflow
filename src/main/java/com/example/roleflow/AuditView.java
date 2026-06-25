package com.example.roleflow;

import java.util.List;

/**
 * A snapshot of an audit trail returned to the audit web page. {@code completed} is false while the run
 * is still being processed (including while it is paused waiting for the user to answer a clarifying
 * question), so the page knows whether to keep polling.
 */
public record AuditView(String promptId, String runId, String source, boolean completed,
                        List<AuditEvent> events) {

    /** A placeholder for a prompt id that has not yet been associated with a run. */
    public static AuditView pending(String promptId) {
        return new AuditView(promptId, null, null, false, List.of());
    }
}
