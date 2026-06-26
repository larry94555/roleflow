package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Drives a single user prompt through the roles in {@code roleflow.active}.
 *
 * <p>For each role the engine builds a system prompt from the role's action, sends it together with the
 * (unchanging) user prompt to the model via a {@link ModelInvoker}, parses the structured reply, writes
 * any artifact to {@code goals/}, then follows the role's transition:
 * <ul>
 *   <li>a different role &rarr; continue immediately with the same user prompt;</li>
 *   <li>the same role &rarr; a clarifying question was asked, so return it and wait for the user;</li>
 *   <li>{@code done} or an unknown target &rarr; the run is complete.</li>
 * </ul>
 * Because every model call goes through the shared conversation memory, each role sees the output of the
 * earlier ones, and clarifying questions/answers accumulate as context without disrupting the flow.
 */
@Service
public class RoleFlowEngine {

    /** Makes one model call through the conversation memory. */
    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature)
                throws Exception;
    }

    private final RoleFlowConfig config;
    private final RoleFlowSession session;
    private final GoalFileWriter writer;
    private final SessionLabeler labeler;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final int maxSteps;

    public RoleFlowEngine(RoleFlowConfig config, RoleFlowSession session, GoalFileWriter writer,
                          SessionLabeler labeler, AuditService audit, ObjectMapper mapper,
                          @Value("${roleflow.max-steps:20}") int maxSteps) {
        this.config = config;
        this.session = session;
        this.writer = writer;
        this.labeler = labeler;
        this.audit = audit;
        this.mapper = mapper;
        this.maxSteps = Math.max(1, maxSteps);
    }

    /**
     * Runs the workflow for one user prompt and returns the text to show the user (the message from the
     * role where the run completed or paused for clarification). Every step is recorded to the audit trail
     * for {@code runId}; {@code auditId} (an optional client-supplied id) is linked to that run so the
     * audit web page can find it.
     */
    public String run(String userPrompt, String systemOverride, Integer maxTokens, Double temperature,
                      ModelInvoker model, String auditId, String source) throws Exception {
        boolean fresh = session.isIdle();
        if (fresh) {
            // The run id carries a human-readable prefix summarizing this session's first prompt.
            session.begin(config.firstRole().name(), labeler.newRunId(userPrompt));
        }
        String runId = session.runId();
        if (fresh) {
            audit.runStarted(runId, source);
        }
        audit.link(auditId, runId);

        String lastMessage = "";
        for (int step = 0; step < maxSteps; step++) {
            Role role = config.byName(session.currentRole());
            if (role == null) { // a transition pointed at a role that does not exist
                audit.runCompleted(runId, session.currentRole());
                return complete(lastMessage);
            }

            audit.roleStarted(runId, role.name());
            String systemPrompt = buildSystemPrompt(role, systemOverride);
            audit.modelRequest(runId, role.name(), systemPrompt, userPrompt);

            String raw;
            try {
                raw = model.invoke(systemPrompt, userPrompt, maxTokens, temperature);
            } catch (Exception e) {
                audit.modelError(runId, role.name(), e.getMessage());
                throw e;
            }
            RoleFlowReply reply = RoleFlowReply.parse(raw, mapper);
            audit.modelResponse(runId, role.name(), raw, reply.decision());
            lastMessage = reply.message().isBlank() ? raw.trim() : reply.message();

            if (role.hasOutput()) {
                // The model is supposed to put the file content in "artifact"; smaller models sometimes
                // put it in "message" instead. Fall back to the message so the file is still written.
                String artifact = reply.artifact().isBlank() ? reply.message() : reply.artifact();
                if (!artifact.isBlank()) {
                    String path = writer.write(role.outputKind(), runId, artifact);
                    session.addArtifact(role.outputKind(), artifact, path);
                    audit.artifactWritten(runId, role.name(), role.outputKind(), path);
                }
            }

            String target = role.resolve(reply.decision());
            if (target == null || Role.DONE.equalsIgnoreCase(target)) {
                audit.transition(runId, role.name(),
                        "decision '" + reply.decision() + "' -> done (run complete)");
                audit.runCompleted(runId, role.name());
                return complete(lastMessage);
            }
            if (target.equalsIgnoreCase(role.name())) {
                // Self-transition: the role asked the user something; pause and wait for their reply.
                audit.transition(runId, role.name(),
                        "decision '" + reply.decision() + "' -> " + role.name()
                                + " (same role: asking the user, waiting for a reply)");
                audit.clarificationPause(runId, role.name(), lastMessage);
                return lastMessage;
            }
            if (config.byName(target) == null) {
                audit.transition(runId, role.name(),
                        "decision '" + reply.decision() + "' -> " + target + " (no such role: run complete)");
                audit.runCompleted(runId, role.name());
                return complete(lastMessage);
            }
            audit.transition(runId, role.name(),
                    "decision '" + reply.decision() + "' -> " + config.byName(target).name());
            session.moveTo(config.byName(target).name());
        }

        // Safety cap: stop runaway loops.
        audit.runCompleted(runId, session.currentRole());
        return complete(lastMessage);
    }

    /**
     * Finishes a run: appends the exact {@code file:///} links for any artifacts created (so the file
     * locations are authoritative rather than transcribed by the model), then resets the session. Must
     * read the artifacts before {@link RoleFlowSession#reset()} clears them.
     */
    private String complete(String lastMessage) {
        String message = withArtifactLinks(lastMessage);
        session.reset();
        return message;
    }

    private String withArtifactLinks(String message) {
        Map<String, String> locations = session.artifactPaths();
        if (locations.isEmpty()) {
            return message;
        }
        StringBuilder builder = new StringBuilder(message == null ? "" : message);
        builder.append("\n\nFiles created:");
        for (Map.Entry<String, String> entry : locations.entrySet()) {
            builder.append("\n- ").append(capitalize(entry.getKey())).append(" file: ").append(entry.getValue());
        }
        return builder.toString();
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    String buildSystemPrompt(Role role, String systemOverride) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are RoleFlow, an assistant that processes a user's prompt through a sequence ")
                .append("of roles. Perform ONLY the current role described below; later roles are handled ")
                .append("in separate steps.\n\n");
        prompt.append("Current role: ").append(role.name());
        if (role.title() != null && !role.title().isBlank()) {
            prompt.append(" (").append(role.title()).append(")");
        }
        prompt.append("\n\n");
        prompt.append("Task for this step:\n").append(role.action()).append("\n\n");
        prompt.append("Allowed values for \"decision\": ")
                .append(String.join(", ", role.allowedDecisions())).append("\n");
        if (role.hasOutput()) {
            prompt.append("This step produces a ").append(role.outputKind())
                    .append(" artifact: put its full human-readable content in \"artifact\".\n");
        }

        Map<String, String> locations = session.artifactPaths();
        if (!locations.isEmpty()) {
            prompt.append("\nFiles already created in this request:\n");
            for (Map.Entry<String, String> entry : locations.entrySet()) {
                prompt.append("- ").append(entry.getKey()).append(" file: ").append(entry.getValue()).append("\n");
            }
            if (role.hasOutput()) {
                // A role that builds on prior artifacts (e.g. the plan uses the goal) gets their content.
                Map<String, String> contents = session.artifactContents();
                prompt.append("\nContent of those files, for your reference ")
                        .append("(do not copy it verbatim into your reply):\n");
                for (Map.Entry<String, String> entry : contents.entrySet()) {
                    prompt.append("--- ").append(entry.getKey()).append(" ---\n")
                            .append(entry.getValue()).append("\n");
                }
            } else {
                // A reporting role only needs the locations; withholding the content stops it from
                // pasting a file's body where its path belongs. The exact links are appended by the
                // engine when the run completes (see withArtifactLinks).
                prompt.append("Report these file locations to the user. ")
                        .append("Do not paste the file contents.\n");
            }
        }

        if (systemOverride != null && !systemOverride.isBlank()) {
            prompt.append("\nAdditional instructions from the user: ").append(systemOverride).append("\n");
        }

        prompt.append("\nRespond with ONLY a single JSON object and nothing else, in exactly this form:\n");
        prompt.append("{\"message\": \"<text to show the user>\", \"decision\": \"<one allowed decision>\", ")
                .append("\"artifact\": \"<file content, or empty string>\"}");
        return prompt.toString();
    }
}
