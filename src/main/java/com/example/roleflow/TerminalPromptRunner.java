package com.example.roleflow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads prompts interactively from the terminal (stdin) and prints the model's reply. Runs on a daemon
 * thread so it never blocks application startup, and is disabled automatically during tests via
 * {@code roleflow.terminal.enabled=false}. The {@link #loop} method is package-private so it can be
 * driven by a unit test with in-memory streams.
 *
 * <p>To bring the command line closer to the web UI, file links in the reply are rendered as clickable
 * terminal hyperlinks pointing at the server's rendered view, and each prompt's audit trail is offered as a
 * clickable link (and opened once per session in the browser, like the web page does).
 */
@Component
public class TerminalPromptRunner implements CommandLineRunner {
    private final ConversationService conversation;
    private final TerminalHyperlinks links;
    private final boolean openAudit;

    /** How a prompt's audit page is opened in the browser; overridable so tests don't spawn a browser. */
    private Consumer<String> browser = TerminalPromptRunner::openInBrowser;
    /** Source of per-prompt audit ids; overridable for deterministic tests. */
    private Supplier<String> auditIds = () -> UUID.randomUUID().toString();
    private boolean auditOpened;

    @Value("${roleflow.terminal.enabled:true}") private boolean enabled = true;

    @Autowired
    public TerminalPromptRunner(ConversationService conversation,
                                @Value("${server.port:8080}") int port,
                                @Value("${roleflow.terminal.base-url:}") String baseUrl,
                                @Value("${roleflow.terminal.hyperlinks:true}") boolean hyperlinks,
                                @Value("${roleflow.terminal.open-audit:true}") boolean openAudit) {
        this.conversation = conversation;
        String resolved = baseUrl == null || baseUrl.isBlank() ? "http://localhost:" + port : baseUrl;
        this.links = new TerminalHyperlinks(resolved, hyperlinks);
        this.openAudit = openAudit;
    }

    /** Test convenience: hyperlinks on, browser auto-open off (so tests never spawn a browser). */
    TerminalPromptRunner(ConversationService conversation) {
        this(conversation, 8080, "", true, false);
    }

    void setBrowser(Consumer<String> browser) {
        this.browser = browser;
    }

    void setAuditIds(Supplier<String> auditIds) {
        this.auditIds = auditIds;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        Thread thread = new Thread(
                () -> loop(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out),
                "roleflow-terminal");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Prompt/response loop. Reads one line at a time, sends it to llama-server, and prints the reply with
     * clickable file/audit links. Stops on end-of-input or when the user types {@code exit} or {@code quit}.
     */
    private static final String READY_PROMPT = "RoleFlow> ";
    // Shown as the prompt when the workflow paused for a clarifying answer, so the user knows to reply.
    private static final String REPLY_PROMPT = "waiting for a reply... > ";
    // Shown while the agent works (and then erased), so the user knows it is busy, not waiting on them.
    private static final String THINKING = "thinking...";

    void loop(BufferedReader in, PrintStream out) {
        out.println("RoleFlow terminal ready. Type a prompt and press Enter (type 'exit' or 'quit' to stop).");
        try {
            String line;
            out.print(promptFor());
            out.flush();
            while ((line = in.readLine()) != null) {
                String prompt = line.trim();
                if (prompt.equalsIgnoreCase("exit") || prompt.equalsIgnoreCase("quit")) {
                    break;
                }
                if (!prompt.isEmpty()) {
                    String auditId = auditIds.get();
                    out.print(THINKING);   // the agent is working; the user is not expected to type yet
                    out.flush();
                    try {
                        String reply = conversation.reply(null, prompt, null, null, auditId, "terminal");
                        eraseThinking(out);
                        out.println(links.linkifyFiles(reply));
                        out.println(links.auditLine(auditId));
                        openAuditOnce(auditId);
                    } catch (Exception e) {
                        eraseThinking(out);
                        out.println("[error] " + e.getMessage());
                    }
                }
                // The prompt now reflects whether the agent is waiting for the user to reply.
                out.print(promptFor());
                out.flush();
            }
        } catch (Exception e) {
            out.println("[error] terminal input closed: " + e.getMessage());
        }
    }

    /** "waiting for a reply..." when the workflow paused for a clarifying answer, otherwise the ready prompt. */
    private String promptFor() {
        boolean awaiting;
        try {
            awaiting = conversation.awaitingReply();
        } catch (Exception e) {
            awaiting = false;
        }
        return awaiting ? REPLY_PROMPT : READY_PROMPT;
    }

    /** Clears the "thinking..." cue from the current line before the reply is printed. */
    private static void eraseThinking(PrintStream out) {
        out.print("\r" + " ".repeat(THINKING.length()) + "\r");
    }

    /** Opens the audit page in the browser once per session (like the web page), if enabled. */
    private void openAuditOnce(String auditId) {
        if (openAudit && !auditOpened) {
            auditOpened = true;
            browser.accept(links.auditUrl(auditId));
        }
    }

    /** Best-effort: open {@code url} in the default browser; a no-op when that is unavailable (headless). */
    private static void openInBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(URI.create(url));
                }
            }
        } catch (Throwable ignored) {
            // No desktop/browser available — the printed link is the fallback.
        }
    }
}
