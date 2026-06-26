package com.example.roleflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalFileWriterTest {

    @Test
    void writesArtifactNamedByKindAndRunIdAndReturnsFileUrl(@TempDir Path dir) throws Exception {
        GoalFileWriter writer = new GoalFileWriter(dir);

        String url = writer.write("goal", "20260625-101010-abcd", "# Goal\nDo the thing.");

        Path expected = dir.resolve("goal-20260625-101010-abcd.md");
        assertTrue(Files.exists(expected), "the goal file should be created");
        assertEquals("# Goal\nDo the thing.", Files.readString(expected));
        // The returned location is an absolute file:// URL ending in the file name.
        assertTrue(url.startsWith("file:"), "returned location should be a file URL: " + url);
        assertTrue(url.endsWith("goal-20260625-101010-abcd.md"));
        assertEquals(expected.toUri().toString(), url);
    }

    @Test
    void createsDirectoryIfMissing(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("nested/goals");
        GoalFileWriter writer = new GoalFileWriter(nested);

        writer.write("plan", "run1", "content");

        assertTrue(Files.exists(nested.resolve("plan-run1.md")));
    }

    @Test
    void sanitizesUnsafeNameCharacters(@TempDir Path dir) throws Exception {
        GoalFileWriter writer = new GoalFileWriter(dir);

        writer.write("Goal Kind!", "a/b c", "x");

        // Spaces and unsafe characters become hyphens; everything is lower-cased.
        assertTrue(Files.exists(dir.resolve("goal-kind--a-b-c.md")));
    }
}
