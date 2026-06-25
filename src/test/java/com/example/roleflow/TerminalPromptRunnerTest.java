package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static class StubLlamaClient extends LlamaClient {
        final List<String> prompts = new ArrayList<>();
        boolean fail;

        StubLlamaClient() {
            super(new ObjectMapper());
        }

        @Override
        public String ask(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature) {
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
        StubLlamaClient llama = new StubLlamaClient();
        TerminalPromptRunner runner = new TerminalPromptRunner(llama);

        String output = runLoop(runner, "hello\nworld\n");

        assertEquals(List.of("hello", "world"), llama.prompts);
        assertTrue(output.contains("echo:hello"));
        assertTrue(output.contains("echo:world"));
    }

    @Test
    void stopsOnExitCommand() {
        StubLlamaClient llama = new StubLlamaClient();
        TerminalPromptRunner runner = new TerminalPromptRunner(llama);

        runLoop(runner, "first\nexit\nshould-not-run\n");

        assertEquals(List.of("first"), llama.prompts);
    }

    @Test
    void skipsBlankLines() {
        StubLlamaClient llama = new StubLlamaClient();
        TerminalPromptRunner runner = new TerminalPromptRunner(llama);

        runLoop(runner, "\n   \nreal\n");

        assertEquals(List.of("real"), llama.prompts);
    }

    @Test
    void reportsErrorsWithoutCrashingTheLoop() {
        StubLlamaClient llama = new StubLlamaClient();
        llama.fail = true;
        TerminalPromptRunner runner = new TerminalPromptRunner(llama);

        String output = runLoop(runner, "boom\n");

        assertTrue(output.contains("[error] server down"));
    }

    @Test
    void usesConfiguredSystemPrompt() {
        StubLlamaClient llama = new StubLlamaClient() {
            String capturedSystem;
            @Override
            public String ask(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature) {
                capturedSystem = systemPrompt;
                assertEquals("Be brief", capturedSystem);
                return super.ask(systemPrompt, userPrompt, maxTokens, temperature);
            }
        };
        TerminalPromptRunner runner = new TerminalPromptRunner(llama);
        setField(runner, "systemPrompt", "Be brief");

        runLoop(runner, "hi\n");

        assertEquals(List.of("hi"), llama.prompts);
    }

    @Test
    void disabledRunnerDoesNotReadStdin() {
        StubLlamaClient llama = new StubLlamaClient();
        TerminalPromptRunner runner = new TerminalPromptRunner(llama);
        setField(runner, "enabled", false);

        runner.run();

        assertTrue(llama.prompts.isEmpty());
    }
}
