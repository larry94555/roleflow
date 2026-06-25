package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSummarizerTest {

    /** Captures what gets sent to the model and returns a canned summary (or fails on demand). */
    private static class CapturingLlamaClient extends LlamaClient {
        String capturedSystem;
        String capturedUser;
        Double capturedTemperature;
        String reply = "condensed summary";
        boolean fail;

        CapturingLlamaClient() {
            super(new ObjectMapper());
        }

        @Override
        public String ask(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature) {
            capturedSystem = systemPrompt;
            capturedUser = userPrompt;
            capturedTemperature = temperature;
            if (fail) throw new IllegalStateException("model unavailable");
            return reply;
        }
    }

    @Test
    void buildsTranscriptFromPreviousSummaryAndMessages() {
        CapturingLlamaClient llama = new CapturingLlamaClient();
        LlmSummarizer summarizer = new LlmSummarizer(llama, 256);

        String result = summarizer.summarize("earlier summary",
                List.of(Message.user("what is the capital of France?"),
                        Message.assistant("Paris")));

        assertEquals("condensed summary", result);
        assertEquals(LlmSummarizer.SUMMARY_SYSTEM_PROMPT, llama.capturedSystem);
        assertEquals(0.0, llama.capturedTemperature);
        assertTrue(llama.capturedUser.contains("earlier summary"));
        assertTrue(llama.capturedUser.contains("user: what is the capital of France?"));
        assertTrue(llama.capturedUser.contains("assistant: Paris"));
    }

    @Test
    void omitsPreviousSummaryHeaderWhenNone() {
        CapturingLlamaClient llama = new CapturingLlamaClient();
        LlmSummarizer summarizer = new LlmSummarizer(llama, 256);

        summarizer.summarize("", List.of(Message.user("hello")));

        assertTrue(llama.capturedUser.startsWith("New messages to fold"));
    }

    @Test
    void fallsBackToPreviousSummaryOnFailure() {
        CapturingLlamaClient llama = new CapturingLlamaClient();
        llama.fail = true;
        LlmSummarizer summarizer = new LlmSummarizer(llama, 256);

        String result = summarizer.summarize("keep me", List.of(Message.user("lost turn")));

        assertEquals("keep me", result);
    }

    @Test
    void fallsBackToEmptyWhenNoPreviousSummaryAndFailure() {
        CapturingLlamaClient llama = new CapturingLlamaClient();
        llama.fail = true;
        LlmSummarizer summarizer = new LlmSummarizer(llama, 256);

        assertEquals("", summarizer.summarize(null, List.of(Message.user("x"))));
    }
}
