package com.example.roleflow;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Serves audit trails as JSON for the audit web page ({@code audit.html}). The page polls one of these
 * endpoints while a prompt is processed and renders the events as they arrive.
 */
@RestController
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    /** Trail for a client-supplied prompt id (returns a pending view until the run is linked). */
    @GetMapping("/audit/{promptId}")
    public AuditView byPrompt(@PathVariable String promptId) {
        return audit.viewByPrompt(promptId);
    }

    /** Trail for a workflow run id (e.g. the id printed in the audit log for a terminal prompt). */
    @GetMapping("/audit/run/{runId}")
    public AuditView byRun(@PathVariable String runId) {
        AuditView view = audit.viewByRun(runId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no audit trail for run " + runId);
        }
        return view;
    }

    /** Recent runs, newest first, for the audit index page. */
    @GetMapping("/audit")
    public List<AuditView> recent() {
        return audit.recent();
    }
}
