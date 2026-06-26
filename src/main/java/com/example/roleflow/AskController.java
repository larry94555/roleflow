package com.example.roleflow;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST entry point. {@code POST /ask} accepts a user prompt (plus an optional system prompt,
 * max-tokens, and temperature) and returns the model's reply as {@code {"response": "..."}}.
 * Requests run through {@link ConversationService}, so each prompt sees the context of the ones before
 * it. The bundled web page and the {@code ask} helper scripts both call this endpoint.
 */
@RestController
public class AskController {
    private final ConversationService conversation;

    public AskController(ConversationService conversation) {
        this.conversation = conversation;
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody AskRequest request) throws Exception {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String auditId = request.auditId();
        String response = conversation.reply(
                request.system(), request.prompt(), request.maxTokens(), request.temperature(),
                auditId, "web");
        // Echo the audit id back so the web page can link to this prompt's audit trail.
        if (auditId != null && !auditId.isBlank()) {
            return Map.of("response", response, "auditId", auditId);
        }
        return Map.of("response", response);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }

    public record AskRequest(String prompt, String system, Integer maxTokens, Double temperature,
                             String auditId) {}
}
