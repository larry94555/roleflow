package com.example.roleflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates a user prompt against the session {@link ConversationMemory}. When {@code roleflow.active}
 * defines a workflow, the prompt is driven through the roles by {@link RoleFlowEngine} (one model call per
 * role, with clarification pauses and goal/plan file output). Otherwise it falls back to a single model
 * call. Both the web endpoint and the terminal reader go through here, so they share one conversation and
 * one workflow.
 */
@Service
public class ConversationService {

    private final ConversationMemory memory;
    private final LlamaClient llama;
    private final RoleFlowConfig roleFlow;
    private final RoleFlowEngine engine;
    private final AuditService audit;
    private final int defaultResponseTokens;
    private final String defaultSystem;

    public ConversationService(ConversationMemory memory, LlamaClient llama, RoleFlowConfig roleFlow,
                               RoleFlowEngine engine, AuditService audit,
                               @Value("${prompt.max-tokens:1024}") int defaultResponseTokens,
                               @Value("${roleflow.system-prompt:}") String defaultSystem) {
        this.memory = memory;
        this.llama = llama;
        this.roleFlow = roleFlow;
        this.engine = engine;
        this.audit = audit;
        this.defaultResponseTokens = defaultResponseTokens;
        this.defaultSystem = defaultSystem == null ? "" : defaultSystem;
    }

    /** Convenience overload with no audit correlation id (used by internal callers and tests). */
    public String reply(String systemOverride, String userPrompt, Integer maxTokens, Double temperature)
            throws Exception {
        return reply(systemOverride, userPrompt, maxTokens, temperature, null, "internal");
    }

    /**
     * Processes {@code userPrompt} and returns the text to show the user.
     *
     * @param systemOverride optional per-call system prompt / extra instructions
     * @param maxTokens      optional response-length cap; when null the configured default is used
     * @param temperature    optional sampling temperature
     * @param auditId        optional client-supplied id used to find this prompt's audit trail
     * @param source         where the prompt came from (e.g. "web", "terminal"), for the audit log
     */
    public String reply(String systemOverride, String userPrompt, Integer maxTokens, Double temperature,
                        String auditId, String source) throws Exception {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }

        // Hold the session lock across the whole workflow so the shared memory and flow state stay
        // consistent even if the web page and terminal are used at the same time.
        synchronized (memory) {
            if (roleFlow.isActive()) {
                return engine.run(userPrompt, systemOverride, maxTokens, temperature, this::runModel,
                        auditId, source);
            }
            return runPlain(systemOverride, userPrompt, maxTokens, temperature, auditId, source);
        }
    }

    /** Single-call path (no workflow), still audited under a synthetic run id so the web view works. */
    private String runPlain(String systemOverride, String userPrompt, Integer maxTokens, Double temperature,
                            String auditId, String source) throws Exception {
        String runId = "plain-" + UUID.randomUUID();
        audit.runStarted(runId, source);
        audit.link(auditId, runId);
        audit.roleStarted(runId, "Model");
        String system = systemOverride != null ? systemOverride : defaultSystem;
        audit.modelRequest(runId, "Model", system, userPrompt);
        try {
            String reply = runModel(system, userPrompt, maxTokens, temperature);
            audit.modelResponse(runId, "Model", reply, "");
            audit.runCompleted(runId, "Model");
            return reply;
        } catch (Exception e) {
            audit.modelError(runId, "Model", e.getMessage());
            throw e;
        }
    }

    /**
     * Makes one model call through the conversation memory: assemble a budget-fitting request, send it,
     * and record the exchange so it becomes context for the next call (including the next role).
     */
    private String runModel(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature)
            throws Exception {
        int responseReserve = maxTokens != null && maxTokens > 0 ? maxTokens : defaultResponseTokens;
        List<Message> toSend = memory.prepareRequest(systemPrompt, userPrompt, responseReserve);
        List<Map<String, Object>> payload = toSend.stream().map(Message::toMap).toList();
        String reply = llama.chat(payload, maxTokens, temperature);
        memory.recordExchange(userPrompt, reply);
        return reply;
    }
}
