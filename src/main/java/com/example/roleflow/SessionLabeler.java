package com.example.roleflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds a short, human-readable, session-unique run id of the form {@code <prefix>_<timestamp>}.
 *
 * <p>The {@code prefix} summarizes the session's initial prompt (e.g. "Search the first 10,000 integers
 * for a counterexample of Legendre's conjecture" &rarr; {@code search-integers-counterexample}). Prefixes
 * are kept unique: if the same prefix would be produced again, a number is appended ({@code legendre},
 * {@code legendre-2}, …). The run id is used in goal/plan file names and in every audit log line, so a
 * whole session can be found with a single {@code grep <prefix>_} (or Splunk search).
 *
 * <p>Known prefixes are seeded from the existing goal/plan files at startup so numbering stays unique
 * across application restarts.
 */
@Component
public class SessionLabeler {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_WORDS = 3;
    private static final int MAX_LENGTH = 24;

    /** Words too generic to help identify a session. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "to", "of", "for", "in", "on", "at", "by", "with",
            "from", "as", "is", "are", "was", "were", "be", "am", "do", "does", "did", "i", "me", "my",
            "we", "our", "us", "you", "your", "it", "this", "that", "these", "those", "please", "can",
            "could", "would", "should", "will", "want", "need", "let");

    /** Recovers prefixes from existing files: {@code (goal|plan)_<prefix>_<timestamp>.md}. */
    private static final Pattern ARTIFACT = Pattern.compile("(?:goal|plan)_(.+)_\\d{8}-\\d{6}\\.md");

    private final Set<String> usedPrefixes = new HashSet<>();

    public SessionLabeler(@Value("${roleflow.goals-dir:goals}") String goalsDir) {
        seedFromExistingFiles(Path.of(goalsDir));
    }

    /** Returns a unique {@code <prefix>_<timestamp>} run id summarizing {@code initialPrompt}. */
    public synchronized String newRunId(String initialPrompt) {
        String prefix = uniquify(slug(initialPrompt));
        usedPrefixes.add(prefix);
        return prefix + "_" + LocalDateTime.now().format(TIMESTAMP);
    }

    /** The prefix portion of a run id produced by {@link #newRunId} (everything before the timestamp). */
    public static String prefixOf(String runId) {
        if (runId == null) return null;
        int underscore = runId.lastIndexOf('_');
        return underscore > 0 ? runId.substring(0, underscore) : runId;
    }

    String slug(String prompt) {
        if (prompt == null || prompt.isBlank()) return "session";
        String[] words = prompt.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        String slug = pick(words, true);               // prefer content words
        if (slug.isEmpty()) slug = pick(words, false);  // otherwise take the first words as-is
        if (slug.isEmpty()) return "session";
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH);
            int dash = slug.lastIndexOf('-');
            if (dash >= 3) slug = slug.substring(0, dash); // avoid cutting a word in half
            slug = slug.replaceAll("-+$", "");
        }
        return slug;
    }

    private static String pick(String[] words, boolean contentOnly) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (contentOnly && (word.length() < 3 || STOPWORDS.contains(word))) continue;
            if (builder.length() > 0) builder.append('-');
            builder.append(word);
            if (++count >= MAX_WORDS) break;
        }
        return builder.toString();
    }

    private String uniquify(String base) {
        if (!usedPrefixes.contains(base)) return base;
        int n = 2;
        while (usedPrefixes.contains(base + "-" + n)) n++;
        return base + "-" + n;
    }

    private void seedFromExistingFiles(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.map(path -> path.getFileName().toString())
                    .map(ARTIFACT::matcher)
                    .filter(Matcher::matches)
                    .forEach(matcher -> usedPrefixes.add(matcher.group(1)));
        } catch (IOException ignored) {
            // If the directory cannot be listed, start with an empty set; uniqueness still holds in-process.
        }
    }
}
