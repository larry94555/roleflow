package com.example.roleflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Serves the goal and plan files from the goals directory over HTTP so the file links shown in the web
 * view are clickable. A raw {@code file://} link cannot be opened from an {@code http} page (browsers
 * block it), so the web page rewrites those links to {@code /goals/<name>}, which this controller serves
 * inline as text.
 */
@RestController
public class GoalFileController {

    // A plain file name ending in .md — no path separators, so directory traversal is impossible.
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.md");

    private final Path goalsDir;

    public GoalFileController(@Value("${roleflow.goals-dir:goals}") String goalsDir) {
        this.goalsDir = Path.of(goalsDir).toAbsolutePath().normalize();
    }

    @GetMapping("/goals/{name}")
    public ResponseEntity<Resource> goalFile(@PathVariable String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name");
        }
        Path file = goalsDir.resolve(name).normalize();
        if (!file.startsWith(goalsDir) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such file: " + name);
        }
        // text/plain so the browser shows the file inline instead of downloading it.
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(new FileSystemResource(file));
    }
}
