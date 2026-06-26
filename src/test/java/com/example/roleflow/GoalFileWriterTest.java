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

        String url = writer.write("goal", "legendre_20260625-101010", "# Goal\nDo the thing.");

        // <kind>_<runId>.md, where the run id is <prefix>_<timestamp>.
        Path expected = dir.resolve("goal_legendre_20260625-101010.md");
        assertTrue(Files.exists(expected), "the goal file should be created");
        assertEquals("# Goal\nDo the thing.", Files.readString(expected));
        // The returned location is an absolute file:// URL ending in the file name.
        assertTrue(url.startsWith("file:"), "returned location should be a file URL: " + url);
        assertTrue(url.endsWith("goal_legendre_20260625-101010.md"));
        assertEquals(expected.toUri().toString(), url);
    }

    @Test
    void createsDirectoryIfMissing(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("nested/goals");
        GoalFileWriter writer = new GoalFileWriter(nested);

        writer.write("plan", "run1", "content");

        assertTrue(Files.exists(nested.resolve("plan_run1.md")));
    }

    @Test
    void sanitizesUnsafeNameCharacters(@TempDir Path dir) throws Exception {
        GoalFileWriter writer = new GoalFileWriter(dir);

        writer.write("Goal Kind!", "a/b c", "x");

        // Spaces and unsafe characters become hyphens; everything is lower-cased; kind/runId joined by '_'.
        assertTrue(Files.exists(dir.resolve("goal-kind-_a-b-c.md")));
    }
}
