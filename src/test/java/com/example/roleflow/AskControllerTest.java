package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class AskControllerTest {

    /** Captures the arguments the controller forwards to the client. */
    private static class RecordingLlamaClient extends LlamaClient {
        final List<String> calls = new ArrayList<>();
        String reply = "model reply";

        RecordingLlamaClient() {
            super(new ObjectMapper());
        }

        @Override
        public String ask(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature) {
            calls.add(systemPrompt + "|" + userPrompt + "|" + maxTokens + "|" + temperature);
            return reply;
        }
    }

    @Test
    void forwardsPromptAndReturnsResponse() throws Exception {
        RecordingLlamaClient llama = new RecordingLlamaClient();
        AskController controller = new AskController(llama);

        Map<String, String> response = controller.ask(
                new AskController.AskRequest("Hello", "Be terse", 64, 0.2));

        assertEquals(Map.of("response", "model reply"), response);
        assertEquals(List.of("Be terse|Hello|64|0.2"), llama.calls);
    }

    @Test
    void fallsBackToConfiguredSystemPromptWhenNoneSupplied() throws Exception {
        RecordingLlamaClient llama = new RecordingLlamaClient();
        AskController controller = new AskController(llama);
        setField(controller, "defaultSystem", "Default system");

        controller.ask(new AskController.AskRequest("Hi", null, null, null));

        assertEquals(List.of("Default system|Hi|null|null"), llama.calls);
    }

    @Test
    void rejectsBlankPromptWithoutCallingTheModel() {
        RecordingLlamaClient llama = new RecordingLlamaClient();
        AskController controller = new AskController(llama);

        assertThrows(IllegalArgumentException.class,
                () -> controller.ask(new AskController.AskRequest("   ", null, null, null)));
        assertTrue(llama.calls.isEmpty());
    }

    @Test
    void badRequestHandlerReturnsErrorMessage() {
        AskController controller = new AskController(new RecordingLlamaClient());

        Map<String, String> body = controller.badRequest(new IllegalArgumentException("prompt is required"));

        assertEquals(Map.of("error", "prompt is required"), body);
    }
}
