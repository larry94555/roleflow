package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationServiceTest {

    /** Captures the payloads sent to llama-server and returns canned replies. */
    private static class FakeLlamaClient extends LlamaClient {
        final List<List<Map<String, Object>>> payloads = new ArrayList<>();
        final List<String> replies = new ArrayList<>();
        int call;

        FakeLlamaClient(String... replies) {
            super(new ObjectMapper());
            this.replies.addAll(List.of(replies));
        }

        @Override
        public String chat(List<Map<String, Object>> messages, Integer maxTokens, Double temperature) {
            payloads.add(messages);
            return replies.get(Math.min(call++, replies.size() - 1));
        }
    }

    private ConversationMemory memory(Summarizer summarizer, int maxTokens) {
        return new ConversationMemory(new TokenEstimator(4), summarizer, maxTokens);
    }

    @Test
    void rejectsBlankPrompt() {
        ConversationService service = new ConversationService(
                memory((p, m) -> "", 8192), new FakeLlamaClient("reply"), 1024, "");

        assertThrows(IllegalArgumentException.class, () -> service.reply(null, "  ", null, null));
    }

    @Test
    void appliesDefaultSystemPromptWhenNoOverride() throws Exception {
        FakeLlamaClient llama = new FakeLlamaClient("ok");
        ConversationService service = new ConversationService(
                memory((p, m) -> "", 8192), llama, 1024, "Be helpful");

        service.reply(null, "hi", null, null);

        Map<String, Object> first = llama.payloads.get(0).get(0);
        assertEquals("system", first.get("role"));
        assertTrue(((String) first.get("content")).contains("Be helpful"));
    }

    @Test
    void perCallSystemOverrideWins() throws Exception {
        FakeLlamaClient llama = new FakeLlamaClient("ok");
        ConversationService service = new ConversationService(
                memory((p, m) -> "", 8192), llama, 1024, "Default");

        service.reply("Override system", "hi", null, null);

        Map<String, Object> first = llama.payloads.get(0).get(0);
        assertTrue(((String) first.get("content")).contains("Override system"));
    }

    @Test
    void laterPromptsCarryEarlierTurnsAsContext() throws Exception {
        FakeLlamaClient llama = new FakeLlamaClient("first answer", "second answer");
        ConversationService service = new ConversationService(
                memory((p, m) -> "", 8192), llama, 1024, "");

        assertEquals("first answer", service.reply(null, "what is 2+2?", null, null));
        assertEquals("second answer", service.reply(null, "and times 3?", null, null));

        // The second request must include the first exchange as prior context.
        List<Map<String, Object>> second = llama.payloads.get(1);
        List<String> contents = second.stream().map(m -> (String) m.get("content")).toList();
        assertTrue(contents.contains("what is 2+2?"), "earlier user prompt should be in context");
        assertTrue(contents.contains("first answer"), "earlier reply should be in context");
        assertTrue(contents.contains("and times 3?"), "the new prompt should be present");
    }
}
