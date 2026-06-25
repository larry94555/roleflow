package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskControllerTest {

    /** Captures the arguments the controller forwards to the conversation service. */
    private static class RecordingConversationService extends ConversationService {
        final List<String> calls = new ArrayList<>();
        String reply = "model reply";

        RecordingConversationService() {
            super(null, null, 0, "");
        }

        @Override
        public String reply(String systemOverride, String userPrompt, Integer maxTokens, Double temperature) {
            calls.add(systemOverride + "|" + userPrompt + "|" + maxTokens + "|" + temperature);
            return reply;
        }
    }

    @Test
    void forwardsRequestAndReturnsResponse() throws Exception {
        RecordingConversationService service = new RecordingConversationService();
        AskController controller = new AskController(service);

        Map<String, String> response = controller.ask(
                new AskController.AskRequest("Hello", "Be terse", 64, 0.2));

        assertEquals(Map.of("response", "model reply"), response);
        assertEquals(List.of("Be terse|Hello|64|0.2"), service.calls);
    }

    @Test
    void forwardsNullSystemSoTheServiceCanApplyItsDefault() throws Exception {
        RecordingConversationService service = new RecordingConversationService();
        AskController controller = new AskController(service);

        controller.ask(new AskController.AskRequest("Hi", null, null, null));

        assertEquals(List.of("null|Hi|null|null"), service.calls);
    }

    @Test
    void rejectsBlankPromptWithoutCallingTheService() {
        RecordingConversationService service = new RecordingConversationService();
        AskController controller = new AskController(service);

        assertThrows(IllegalArgumentException.class,
                () -> controller.ask(new AskController.AskRequest("   ", null, null, null)));
        assertTrue(service.calls.isEmpty());
    }

    @Test
    void badRequestHandlerReturnsErrorMessage() {
        AskController controller = new AskController(new RecordingConversationService());

        Map<String, String> body = controller.badRequest(new IllegalArgumentException("prompt is required"));

        assertEquals(Map.of("error", "prompt is required"), body);
    }
}
