package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * Verifies the llama-server command construction. The manager's defaults mirror goalmaker, so the
 * default command should match what goalmaker launches.
 */
class LlamaServerManagerTest {

    private int indexOf(List<String> cmd, String flag) {
        return cmd.indexOf(flag);
    }

    private String valueAfter(List<String> cmd, String flag) {
        int i = indexOf(cmd, flag);
        return i >= 0 && i + 1 < cmd.size() ? cmd.get(i + 1) : null;
    }

    @Test
    void buildsDefaultHuggingFaceCommand() {
        LlamaServerManager manager = new LlamaServerManager();

        List<String> cmd = manager.buildCommand();

        // First token is the resolved binary name.
        assertTrue(cmd.get(0).startsWith("llama-server"));
        // No explicit model path -> pull the small profile model over -hf.
        assertEquals("Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M", valueAfter(cmd, "-hf"));
        assertFalse(cmd.contains("-m"));
        assertEquals("0.0.0.0", valueAfter(cmd, "--host"));
        assertEquals("8081", valueAfter(cmd, "--port"));
        assertEquals("8192", valueAfter(cmd, "-c"));
        assertEquals("qwen2.5-3b-instruct", valueAfter(cmd, "--alias"));
        assertEquals("256", valueAfter(cmd, "--cache-reuse"));
        assertTrue(cmd.contains("--jinja"));
    }

    @Test
    void prefersExplicitModelPathOverHuggingFace() {
        LlamaServerManager manager = new LlamaServerManager();
        setField(manager, "modelPath", "C:/models/model.gguf");

        List<String> cmd = manager.buildCommand();

        assertEquals("C:/models/model.gguf", valueAfter(cmd, "-m"));
        assertFalse(cmd.contains("-hf"));
    }

    @Test
    void honorsExplicitContextAndPort() {
        LlamaServerManager manager = new LlamaServerManager();
        setField(manager, "ctxSize", 4096);
        setField(manager, "port", 9090);

        List<String> cmd = manager.buildCommand();

        assertEquals("4096", valueAfter(cmd, "-c"));
        assertEquals("9090", valueAfter(cmd, "--port"));
    }

    @Test
    void appendsExtraArgsAndDraftModel() {
        LlamaServerManager manager = new LlamaServerManager();
        setField(manager, "extraArgs", "--flash-attn --no-warmup");
        setField(manager, "draftPath", "C:/models/draft.gguf");

        List<String> cmd = manager.buildCommand();

        assertTrue(cmd.contains("--flash-attn"));
        assertTrue(cmd.contains("--no-warmup"));
        assertEquals("C:/models/draft.gguf", valueAfter(cmd, "-md"));
        assertEquals("16", valueAfter(cmd, "--draft-max"));
    }

    @Test
    void resolveBinaryUsesConfiguredBinaryWhenSet() {
        LlamaServerManager manager = new LlamaServerManager();
        setField(manager, "binary", "  /opt/llama/llama-server  ");

        assertEquals("/opt/llama/llama-server", manager.resolveBinary());
    }

    @Test
    void mediumProfileSelectsSevenBillionModel() {
        LlamaServerManager manager = new LlamaServerManager();
        setField(manager, "profile", "medium");

        List<String> cmd = manager.buildCommand();

        assertEquals("Qwen/Qwen2.5-7B-Instruct-GGUF:Q4_K_M", valueAfter(cmd, "-hf"));
    }
}
