package com.example.roleflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Serves the goal-and-plan files from the goals directory over HTTP so the file links shown in the web view
 * are clickable. A raw {@code file://} link cannot be opened from an {@code http} page (browsers block it),
 * so the web page rewrites those links to {@code /goals/<name>}, which this controller serves.
 *
 * <p>By default a {@code .md} file is rendered to a styled HTML page that resembles GitHub's Markdown
 * preview (see {@link MarkdownRenderer}). Appending {@code ?raw=1} returns the unrendered Markdown as plain
 * text instead.
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
    public ResponseEntity<String> goalFile(@PathVariable String name,
                                           @RequestParam(name = "raw", required = false) String raw)
            throws IOException {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name");
        }
        Path file = goalsDir.resolve(name).normalize();
        if (!file.startsWith(goalsDir) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such file: " + name);
        }
        String markdown = Files.readString(file, StandardCharsets.UTF_8);
        if (raw != null) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(markdown);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(renderPage(name, markdown));
    }

    /** Wraps the rendered Markdown body in a minimal page styled to resemble GitHub's Markdown preview. */
    static String renderPage(String title, String markdown) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>" + MarkdownRenderer.escape(title) + "</title>\n"
                + "<style>" + STYLE + "</style></head>\n"
                + "<body><article class=\"markdown-body\">\n"
                + MarkdownRenderer.toHtml(markdown)
                + "\n</article></body></html>";
    }

    // GitHub-preview-like styling: system font, readable measure, light/dark aware, bordered headings.
    private static final String STYLE = """
            :root { color-scheme: light dark; }
            body { margin: 0; padding: 2rem 1rem; background: Canvas; }
            .markdown-body {
              max-width: 860px; margin: 0 auto;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
              font-size: 16px; line-height: 1.6; word-wrap: break-word;
            }
            .markdown-body h1, .markdown-body h2 {
              padding-bottom: .3em; border-bottom: 1px solid #8884; margin-top: 1.5em;
            }
            .markdown-body h1 { font-size: 2em; } .markdown-body h2 { font-size: 1.5em; }
            .markdown-body h3 { font-size: 1.25em; margin-top: 1.4em; }
            .markdown-body ul, .markdown-body ol { padding-left: 2em; }
            .markdown-body li { margin: .25em 0; }
            .markdown-body code {
              background: #8882; padding: .2em .4em; border-radius: 6px;
              font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 85%;
            }
            .markdown-body a { color: #2563eb; }
            """;
}
