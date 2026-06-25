package com.example.roleflow;

import org.springframework.beans.factory.annotation.Value;
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
 * The bundled web page and the {@code ask} helper scripts both call this endpoint.
 */
@RestController
public class AskController {
    private final LlamaClient llama;

    @Value("${roleflow.system-prompt:}") private String defaultSystem = "";

    public AskController(LlamaClient llama) {
        this.llama = llama;
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody AskRequest request) throws Exception {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String system = request.system() != null ? request.system() : defaultSystem;
        String response = llama.ask(system, request.prompt(), request.maxTokens(), request.temperature());
        return Map.of("response", response);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }

    public record AskRequest(String prompt, String system, Integer maxTokens, Double temperature) {}
}
