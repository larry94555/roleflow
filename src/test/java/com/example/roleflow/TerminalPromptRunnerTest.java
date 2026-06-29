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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class TerminalPromptRunnerTest {

    /** Stand-in conversation service that records prompts/audit ids and can simulate failure. */
    private static class StubConversationService extends ConversationService {
        final List<String> prompts = new ArrayList<>();
        final List<String> auditIds = new ArrayList<>();
        boolean fail;
        boolean awaiting;
        String response; // when set, returned verbatim (e.g. to include a file link)

        StubConversationService() {
            super(null, null, null, null, null, 0, "");
        }

        @Override
        public String reply(String systemOverride, String userPrompt, Integer maxTokens, Double temperature,
                            String auditId, String source) {
            prompts.add(userPrompt);
            auditIds.add(auditId);
            if (fail) throw new IllegalStateException("server down");
            return response != null ? response : "echo:" + userPrompt;
        }

        @Override
        public boolean awaitingReply() {
            return awaiting;
        }
    }

    private static final char ESC = (char) 27;

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
    void rendersFileAndAuditLinksAsTerminalHyperlinks() {
        StubConversationService service = new StubConversationService();
        service.response = "Done.\n\nFiles created:\n- Plan file: file:///C:/x/goals/plan_abc.md";
        TerminalPromptRunner runner = new TerminalPromptRunner(service);
        runner.setAuditIds(() -> "PID-7");

        String output = runLoop(runner, "go\n");

        // The plan file link is an OSC 8 hyperlink to the server's rendered view.
        assertTrue(output.contains(ESC + "]8;;http://localhost:8080/goals/plan_abc.md" + ESC + "\\"), output);
        // The prompt's audit trail is offered as a clickable link to the audit web page.
        assertTrue(output.contains("http://localhost:8080/audit.html?prompt=PID-7"), output);
        // The same audit id is passed through to the conversation service for correlation.
        assertEquals(List.of("PID-7"), service.auditIds);
    }

    @Test
    void opensTheAuditPageOnceInTheBrowserPerSession() {
        StubConversationService service = new StubConversationService();
        // Auto-open is off in the test convenience constructor; turn it on for this test.
        TerminalPromptRunner runner = new TerminalPromptRunner(service, 8080, "", true, true);
        List<String> opened = new ArrayList<>();
        runner.setBrowser(opened::add);

        runLoop(runner, "first\nsecond\n");

        assertEquals(1, opened.size(), "the browser should open once per session, not per prompt");
        assertTrue(opened.get(0).contains("/audit.html?prompt="), opened.toString());
    }

    @Test
    void showsThinkingWhileWorkingAndWaitingPromptWhenAReplyIsNeeded() {
        StubConversationService service = new StubConversationService();
        service.response = "Which folder should I back up?";
        service.awaiting = true; // the workflow paused for a clarifying answer
        TerminalPromptRunner runner = new TerminalPromptRunner(service);

        String output = runLoop(runner, "back up my notes\n");

        assertTrue(output.contains("thinking..."), "a 'thinking...' cue should show while the agent works");
        assertTrue(output.contains("waiting for a reply..."), "the prompt should signal a reply is expected");
    }

    @Test
    void showsTheReadyPromptWhenNoReplyIsNeeded() {
        StubConversationService service = new StubConversationService();
        service.awaiting = false; // the run completed; ready for a new request

        String output = runLoop(new TerminalPromptRunner(service), "hello\n");

        assertTrue(output.contains("thinking..."), output);
        assertFalse(output.contains("waiting for a reply..."), "no reply cue when the run is complete");
        assertTrue(output.contains("RoleFlow> "), output);
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
