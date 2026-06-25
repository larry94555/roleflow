package com.example.roleflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects audit trails for prompts as they are processed and exposes them two ways:
 * <ul>
 *   <li><b>Log file</b> — every event is written through the dedicated {@code com.example.roleflow.audit}
 *       logger, which {@code logback-spring.xml} routes to {@code audit.log} so it can be followed with
 *       {@code tail -f}.</li>
 *   <li><b>Web page</b> — trails are kept in memory (most recent {@code roleflow.audit.max-trails}) keyed
 *       by the workflow run id, and served as {@link AuditView}s for the audit page to poll.</li>
 * </ul>
 *
 * <p>A client (the web page) may supply a prompt id with its request; {@link #link} associates that id
 * with the run so the page can find the trail. Because a request's run id persists across clarification
 * rounds, a single trail captures the whole request, including every clarifying iteration.
 */
@Component
public class AuditService {

    /** Dedicated logger; {@code logback-spring.xml} sends this to the audit log file. */
    static final Logger AUDIT = LoggerFactory.getLogger("com.example.roleflow.audit");

    /** In-memory trail for one run. */
    private static final class Trail {
        final String runId;
        final String source;
        final Instant createdAt = Instant.now();
        final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong seq = new AtomicLong();
        volatile boolean completed;

        Trail(String runId, String source) {
            this.runId = runId;
            this.source = source;
        }
    }

    private final Map<String, Trail> trailsByRun;
    private final Map<String, String> runByPrompt;

    public AuditService(@Value("${roleflow.audit.max-trails:50}") int maxTrails) {
        int cap = Math.max(1, maxTrails);
        this.trailsByRun = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Trail> eldest) {
                return size() > cap;
            }
        });
        this.runByPrompt = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cap * 4;
            }
        });
    }

    /** Ensures a trail exists for a run and logs that it started. */
    public void runStarted(String runId, String source) {
        trailsByRun.computeIfAbsent(runId, id -> new Trail(id, source == null ? "internal" : source));
        record(runId, AuditEvent.Type.RUN_STARTED, builder().detail(source).build());
    }

    /** Associates a client-supplied prompt id with a run, so the web page can find the trail. */
    public void link(String promptId, String runId) {
        if (promptId != null && !promptId.isBlank()) {
            runByPrompt.put(promptId, runId);
        }
    }

    /** Records that a role started; returns its 1-based iteration number within the run. */
    public int roleStarted(String runId, String role) {
        int iteration = countRoleStarts(runId, role) + 1;
        record(runId, AuditEvent.Type.ROLE_STARTED, builder().role(role).iteration(iteration).build());
        return iteration;
    }

    public void modelRequest(String runId, String role, String systemPrompt, String userPrompt) {
        record(runId, AuditEvent.Type.MODEL_REQUEST,
                builder().role(role).systemPrompt(systemPrompt).userPrompt(userPrompt).build());
    }

    public void modelResponse(String runId, String role, String response, String decision) {
        record(runId, AuditEvent.Type.MODEL_RESPONSE,
                builder().role(role).response(response).decision(decision).build());
    }

    public void artifactWritten(String runId, String role, String kind, String path) {
        record(runId, AuditEvent.Type.ARTIFACT_WRITTEN,
                builder().role(role).detail("kind=" + kind + " path=" + path).build());
    }

    public void transition(String runId, String role, String description) {
        record(runId, AuditEvent.Type.TRANSITION, builder().role(role).transition(description).build());
    }

    public void clarificationPause(String runId, String role, String question) {
        record(runId, AuditEvent.Type.CLARIFICATION_PAUSE, builder().role(role).detail(question).build());
    }

    public void runCompleted(String runId, String finalRole) {
        Trail trail = trailsByRun.get(runId);
        if (trail != null) trail.completed = true;
        record(runId, AuditEvent.Type.RUN_COMPLETED, builder().role(finalRole).build());
    }

    public void modelError(String runId, String role, String message) {
        record(runId, AuditEvent.Type.MODEL_ERROR, builder().role(role).detail(message).build());
    }

    /** The trail for a client prompt id, or a pending view if it is not yet associated with a run. */
    public AuditView viewByPrompt(String promptId) {
        String runId = runByPrompt.get(promptId);
        if (runId == null) return AuditView.pending(promptId);
        AuditView view = viewByRun(runId);
        return new AuditView(promptId, view.runId(), view.source(), view.completed(), view.events());
    }

    /** The trail for a run id, or null when unknown. */
    public AuditView viewByRun(String runId) {
        Trail trail = trailsByRun.get(runId);
        if (trail == null) return null;
        synchronized (trail.events) {
            return new AuditView(null, trail.runId, trail.source, trail.completed,
                    List.copyOf(trail.events));
        }
    }

    /** Recent runs, newest first, for discovery (used by the audit index page). */
    public List<AuditView> recent() {
        List<AuditView> views = new ArrayList<>();
        synchronized (trailsByRun) {
            for (Trail trail : trailsByRun.values()) {
                synchronized (trail.events) {
                    views.add(new AuditView(null, trail.runId, trail.source, trail.completed,
                            List.copyOf(trail.events)));
                }
            }
        }
        Collections.reverse(views);
        return views;
    }

    private int countRoleStarts(String runId, String role) {
        Trail trail = trailsByRun.get(runId);
        if (trail == null) return 0;
        int count = 0;
        synchronized (trail.events) {
            for (AuditEvent event : trail.events) {
                if (event.type() == AuditEvent.Type.ROLE_STARTED && role.equals(event.role())) count++;
            }
        }
        return count;
    }

    private void record(String runId, AuditEvent.Type type, EventFields fields) {
        Trail trail = trailsByRun.computeIfAbsent(runId, id -> new Trail(id, "internal"));
        AuditEvent event = new AuditEvent(trail.seq.incrementAndGet(), Instant.now().toString(), type,
                fields.role, fields.iteration, fields.systemPrompt, fields.userPrompt, fields.response,
                fields.decision, fields.transition, fields.detail);
        trail.events.add(event);
        AUDIT.info("{}", format(runId, event));
    }

    /** Renders an event as a human-readable, tail-friendly log line (multi-line for big fields). */
    static String format(String runId, AuditEvent event) {
        StringBuilder line = new StringBuilder("[audit run=").append(runId)
                .append(" #").append(event.seq()).append("] ").append(event.type());
        switch (event.type()) {
            case RUN_STARTED -> line.append(" source=").append(orDash(event.detail()));
            case ROLE_STARTED -> line.append(" role=").append(event.role())
                    .append(" iteration=").append(event.iteration());
            case MODEL_REQUEST -> line.append(" role=").append(event.role())
                    .append("\n    system-prompt: ").append(indent(event.systemPrompt()))
                    .append("\n    user-prompt: ").append(indent(event.userPrompt()));
            case MODEL_RESPONSE -> line.append(" role=").append(event.role())
                    .append(" decision=").append(orDash(event.decision()))
                    .append("\n    response: ").append(indent(event.response()));
            case ARTIFACT_WRITTEN -> line.append(" role=").append(event.role())
                    .append(" ").append(orDash(event.detail()));
            case TRANSITION -> line.append(" role=").append(event.role())
                    .append(" ").append(orDash(event.transition()));
            case CLARIFICATION_PAUSE -> line.append(" role=").append(event.role())
                    .append(" (awaiting user)\n    question: ").append(indent(event.detail()));
            case RUN_COMPLETED -> line.append(" finalRole=").append(orDash(event.role()));
            case MODEL_ERROR -> line.append(" role=").append(event.role())
                    .append(" error: ").append(orDash(event.detail()));
        }
        return line.toString();
    }

    private static String indent(String text) {
        if (text == null || text.isEmpty()) return "(none)";
        return text.replace("\n", "\n        ");
    }

    private static String orDash(String text) {
        return text == null || text.isEmpty() ? "-" : text;
    }

    // --- tiny field carrier to keep record() calls readable ---

    private static EventFields builder() {
        return new EventFields();
    }

    private static final class EventFields {
        String role;
        Integer iteration;
        String systemPrompt;
        String userPrompt;
        String response;
        String decision;
        String transition;
        String detail;

        EventFields role(String v) { this.role = v; return this; }
        EventFields iteration(Integer v) { this.iteration = v; return this; }
        EventFields systemPrompt(String v) { this.systemPrompt = v; return this; }
        EventFields userPrompt(String v) { this.userPrompt = v; return this; }
        EventFields response(String v) { this.response = v; return this; }
        EventFields decision(String v) { this.decision = v; return this; }
        EventFields transition(String v) { this.transition = v; return this; }
        EventFields detail(String v) { this.detail = v; return this; }
        EventFields build() { return this; }
    }
}
