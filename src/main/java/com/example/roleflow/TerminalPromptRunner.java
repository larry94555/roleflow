package com.example.roleflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads prompts interactively from the terminal (stdin) and prints the model's reply. Runs on a daemon
 * thread so it never blocks application startup, and is disabled automatically during tests via
 * {@code roleflow.terminal.enabled=false}. The {@link #loop} method is package-private so it can be
 * driven by a unit test with in-memory streams.
 */
@Component
public class TerminalPromptRunner implements CommandLineRunner {
    private final ConversationService conversation;

    @Value("${roleflow.terminal.enabled:true}") private boolean enabled = true;

    public TerminalPromptRunner(ConversationService conversation) {
        this.conversation = conversation;
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
     * Prompt/response loop. Reads one line at a time, sends it to llama-server, and prints the reply.
     * Stops on end-of-input or when the user types {@code exit} or {@code quit}.
     */
    void loop(BufferedReader in, PrintStream out) {
        out.println("RoleFlow terminal ready. Type a prompt and press Enter (type 'exit' or 'quit' to stop).");
        try {
            String line;
            out.print("RoleFlow> ");
            out.flush();
            while ((line = in.readLine()) != null) {
                String prompt = line.trim();
                if (prompt.equalsIgnoreCase("exit") || prompt.equalsIgnoreCase("quit")) {
                    break;
                }
                if (!prompt.isEmpty()) {
                    try {
                        out.println(conversation.reply(null, prompt, null, null, null, "terminal"));
                    } catch (Exception e) {
                        out.println("[error] " + e.getMessage());
                    }
                }
                out.print("RoleFlow> ");
                out.flush();
            }
        } catch (Exception e) {
            out.println("[error] terminal input closed: " + e.getMessage());
        }
    }
}
