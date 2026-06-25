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
    private final ObjectMapper mapper;
    private final int maxSteps;

    public RoleFlowEngine(RoleFlowConfig config, RoleFlowSession session, GoalFileWriter writer,
                          ObjectMapper mapper, @Value("${roleflow.max-steps:20}") int maxSteps) {
        this.config = config;
        this.session = session;
        this.writer = writer;
        this.mapper = mapper;
        this.maxSteps = Math.max(1, maxSteps);
    }

    /**
     * Runs the workflow for one user prompt and returns the text to show the user (the message from the
     * role where the run completed or paused for clarification).
     */
    public String run(String userPrompt, String systemOverride, Integer maxTokens, Double temperature,
                      ModelInvoker model) throws Exception {
        if (session.isIdle()) {
            session.begin(config.firstRole().name());
        }

        String lastMessage = "";
        for (int step = 0; step < maxSteps; step++) {
            Role role = config.byName(session.currentRole());
            if (role == null) { // a transition pointed at a role that does not exist
                session.reset();
                return lastMessage;
            }

            String systemPrompt = buildSystemPrompt(role, systemOverride);
            String raw = model.invoke(systemPrompt, userPrompt, maxTokens, temperature);
            RoleFlowReply reply = RoleFlowReply.parse(raw, mapper);
            lastMessage = reply.message().isBlank() ? raw.trim() : reply.message();

            if (role.hasOutput() && !reply.artifact().isBlank()) {
                String path = writer.write(role.outputKind(), session.runId(), reply.artifact());
                session.addArtifact(role.outputKind(), reply.artifact(), path);
            }

            String target = role.resolve(reply.decision());
            if (target == null || Role.DONE.equalsIgnoreCase(target)) {
                session.reset();
                return lastMessage;
            }
            if (target.equalsIgnoreCase(role.name())) {
                // Self-transition: the role asked the user something; pause and wait for their reply.
                return lastMessage;
            }
            if (config.byName(target) == null) {
                session.reset();
                return lastMessage;
            }
            session.moveTo(config.byName(target).name());
        }

        // Safety cap: stop runaway loops.
        session.reset();
        return lastMessage;
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

        Map<String, String> paths = session.artifactPaths();
        if (!paths.isEmpty()) {
            Map<String, String> contents = session.artifactContents();
            prompt.append("\nArtifacts already created in this request:\n");
            for (Map.Entry<String, String> entry : paths.entrySet()) {
                prompt.append("- ").append(entry.getKey()).append(" file: ").append(entry.getValue())
                        .append("\n").append(contents.getOrDefault(entry.getKey(), "")).append("\n");
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
