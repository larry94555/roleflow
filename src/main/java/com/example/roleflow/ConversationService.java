package com.example.roleflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Coordinates a single prompt against the session {@link ConversationMemory}: it asks the memory for a
 * budget-fitting message list (compacting if needed), sends it to llama-server, then records the new
 * exchange so it becomes context for the next prompt. Both the web endpoint and the terminal reader go
 * through here, so they share one conversation.
 */
@Service
public class ConversationService {

    private final ConversationMemory memory;
    private final LlamaClient llama;
    private final int defaultResponseTokens;
    private final String defaultSystem;

    public ConversationService(ConversationMemory memory, LlamaClient llama,
                               @Value("${prompt.max-tokens:1024}") int defaultResponseTokens,
                               @Value("${roleflow.system-prompt:}") String defaultSystem) {
        this.memory = memory;
        this.llama = llama;
        this.defaultResponseTokens = defaultResponseTokens;
        this.defaultSystem = defaultSystem == null ? "" : defaultSystem;
    }

    /**
     * Sends {@code userPrompt} with the session's accumulated context and returns the model's reply.
     *
     * @param systemOverride optional per-call system prompt; when null the configured default is used
     * @param maxTokens      optional response-length cap; when null the configured default is used
     * @param temperature    optional sampling temperature
     */
    public String reply(String systemOverride, String userPrompt, Integer maxTokens, Double temperature)
            throws Exception {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String system = systemOverride != null ? systemOverride : defaultSystem;
        int responseReserve = maxTokens != null && maxTokens > 0 ? maxTokens : defaultResponseTokens;

        // Hold the session lock across prepare -> send -> record so the shared memory stays consistent
        // even if the web page and terminal are used at the same time.
        synchronized (memory) {
            List<Message> toSend = memory.prepareRequest(system, userPrompt, responseReserve);
            List<Map<String, Object>> payload = toSend.stream().map(Message::toMap).toList();
            String reply = llama.chat(payload, maxTokens, temperature);
            memory.recordExchange(userPrompt, reply);
            return reply;
        }
    }
}
