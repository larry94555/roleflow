package com.example.roleflow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes the human-readable artifacts (goal and plan files) produced by the workflow into the
 * {@code goals/} directory. Files are named {@code <kind>-<runId>.md} so a request's goal and plan share
 * a recognizable id.
 */
@Component
public class GoalFileWriter {

    private final Path directory;

    @Autowired
    public GoalFileWriter(@Value("${roleflow.goals-dir:goals}") String directory) {
        this(Path.of(directory));
    }

    GoalFileWriter(Path directory) {
        this.directory = directory;
    }

    /**
     * Writes {@code content} to {@code <goals-dir>/<kind>-<runId>.md} and returns the file path as a
     * forward-slash string suitable for display.
     */
    public String write(String kind, String runId, String content) throws IOException {
        Files.createDirectories(directory);
        String fileName = sanitize(kind) + "-" + sanitize(runId) + ".md";
        Path file = directory.resolve(fileName);
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        return file.toString().replace('\\', '/');
    }

    private static String sanitize(String value) {
        String cleaned = (value == null ? "" : value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return cleaned.isBlank() ? "artifact" : cleaned;
    }
}
