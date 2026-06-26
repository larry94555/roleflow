package com.example.roleflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLabelerTest {

    private SessionLabeler labeler(Path dir) {
        return new SessionLabeler(dir.toString());
    }

    @Test
    void slugUsesContentWordsAndDropsStopwords(@TempDir Path dir) {
        SessionLabeler labeler = labeler(dir);

        assertEquals("schedule-weekly-backup", labeler.slug("Schedule a weekly backup of my notes"));
        assertEquals("session", labeler.slug("  "));
    }

    @Test
    void slugFallsBackToFirstWordsWhenAllAreStopwords(@TempDir Path dir) {
        SessionLabeler labeler = labeler(dir);

        // Every word is a stopword/short, so the fallback keeps the first few words verbatim.
        assertEquals("to-be-or", labeler.slug("to be or"));
    }

    @Test
    void slugIsTruncatedAtAWordBoundary(@TempDir Path dir) {
        SessionLabeler labeler = labeler(dir);

        String slug = labeler.slug("counterexample application development scaffolding");
        assertTrue(slug.length() <= 24, "slug should be capped: " + slug);
        assertTrue(slug.startsWith("counterexample-application") || slug.equals("counterexample"),
                "slug should not cut a word in half: " + slug);
    }

    @Test
    void newRunIdHasPrefixUnderscoreTimestampShape(@TempDir Path dir) {
        String runId = labeler(dir).newRunId("Back up my notes folder");

        assertTrue(runId.matches("[a-z0-9-]+_\\d{8}-\\d{6}"), "unexpected run id: " + runId);
        assertTrue(runId.startsWith(SessionLabeler.prefixOf(runId) + "_"),
                "run id should start with its prefix followed by '_': " + runId);
    }

    @Test
    void reusedPrefixGetsANumberAppended(@TempDir Path dir) {
        SessionLabeler labeler = labeler(dir);

        String first = labeler.newRunId("Backup the notes");
        String second = labeler.newRunId("Backup the notes");

        assertEquals("backup-notes", SessionLabeler.prefixOf(first));
        assertEquals("backup-notes-2", SessionLabeler.prefixOf(second));
    }

    @Test
    void existingFilesSeedKnownPrefixesAcrossRestarts(@TempDir Path dir) throws Exception {
        // Simulate a prior session having written files with the "backup-notes" prefix.
        Files.writeString(dir.resolve("goal_backup-notes_20260101-000000.md"), "x");
        Files.writeString(dir.resolve("plan_backup-notes_20260101-000000.md"), "x");

        SessionLabeler labeler = labeler(dir);
        String runId = labeler.newRunId("Backup the notes");

        assertEquals("backup-notes-2", SessionLabeler.prefixOf(runId),
                "a prefix already used by files on disk should be numbered");
    }

    @Test
    void prefixOfSplitsOnTheTimestampUnderscore() {
        assertEquals("legendre", SessionLabeler.prefixOf("legendre_20260625-202242"));
        assertEquals("plain-abc", SessionLabeler.prefixOf("plain-abc")); // no timestamp underscore
        assertNull(SessionLabeler.prefixOf(null));
    }
}
