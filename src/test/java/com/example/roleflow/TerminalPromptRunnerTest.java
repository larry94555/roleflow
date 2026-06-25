package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class TerminalPromptRunnerTest {

    /** Stand-in conversation service that records prompts and can simulate failure. */
    private static class StubConversationService extends ConversationService {
        final List<String> prompts = new ArrayList<>();
        boolean fail;

        StubConversationService() {
            super(null, null, null, null, 0, "");
        }

        @Override
        public String reply(String systemOverride, String userPrompt, Integer maxTokens, Double temperature) {
            prompts.add(userPrompt);
            if (fail) throw new IllegalStateException("server down");
            return "echo:" + userPrompt;
        }
    }

    private String runLoop(TerminalPromptRunner runner, String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        runner.loop(new BufferedReader(new StringReader(input)), new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void answersEachPromptUntilEndOfInput() {
        StubConversationService service = new StubConversationService();
        TerminalPromptRunner runner = new TerminalPromptRunner(service);

        String output = runLoop(runner, "hello\nworld\n");

        assertEquals(List.of("hello", "world"), service.prompts);
        assertTrue(output.contains("echo:hello"));
        assertTrue(output.contains("echo:world"));
    }

    @Test
    void stopsOnExitCommand() {
        StubConversationService service = new StubConversationService();
        TerminalPromptRunner runner = new TerminalPromptRunner(service);

        runLoop(runner, "first\nexit\nshould-not-run\n");

        assertEquals(List.of("first"), service.prompts);
    }

    @Test
    void skipsBlankLines() {
        StubConversationService service = new StubConversationService();
        TerminalPromptRunner runner = new TerminalPromptRunner(service);

        runLoop(runner, "\n   \nreal\n");

        assertEquals(List.of("real"), service.prompts);
    }

    @Test
    void reportsErrorsWithoutCrashingTheLoop() {
        StubConversationService service = new StubConversationService();
        service.fail = true;
        TerminalPromptRunner runner = new TerminalPromptRunner(service);

        String output = runLoop(runner, "boom\n");

        assertTrue(output.contains("[error] server down"));
    }

    @Test
    void disabledRunnerDoesNotReadStdin() {
        StubConversationService service = new StubConversationService();
        TerminalPromptRunner runner = new TerminalPromptRunner(service);
        setField(runner, "enabled", false);

        runner.run();

        assertTrue(service.prompts.isEmpty());
    }
}
