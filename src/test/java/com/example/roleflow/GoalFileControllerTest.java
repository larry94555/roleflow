package com.example.roleflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalFileControllerTest {

    @Test
    void rendersAGoalFileAsHtmlByDefault(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("plan-run1.md"), "# Goal\nDo it.\n\n# Plan\n\n- step one");
        GoalFileController controller = new GoalFileController(dir.toString());

        ResponseEntity<String> response = controller.goalFile("plan-run1.md", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        MediaType contentType = response.getHeaders().getContentType();
        assertEquals("text", contentType.getType());
        assertEquals("html", contentType.getSubtype());
        String body = response.getBody();
        assertTrue(body.contains("<h1>Goal</h1>"), body);
        assertTrue(body.contains("<h1>Plan</h1>"), body);
        assertTrue(body.contains("<li>step one</li>"), body);
        assertTrue(body.contains("markdown-body"), "the page should carry GitHub-like styling");
    }

    @Test
    void servesRawMarkdownWhenRawParameterIsPresent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("plan-run1.md"), "# Goal\nDo it.");
        GoalFileController controller = new GoalFileController(dir.toString());

        ResponseEntity<String> response = controller.goalFile("plan-run1.md", "1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("# Goal\nDo it.", response.getBody());
        assertEquals("text", response.getHeaders().getContentType().getType());
        assertEquals("plain", response.getHeaders().getContentType().getSubtype());
    }

    @Test
    void rejectsNamesThatAreNotSimpleMarkdownFiles(@TempDir Path dir) {
        GoalFileController controller = new GoalFileController(dir.toString());

        // Wrong extension and path-traversal attempts are rejected before touching the filesystem.
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.goalFile("notes.txt", null)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.goalFile("../secret.md", null)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.goalFile("a/b.md", null)));
    }

    @Test
    void unknownFileYieldsNotFound(@TempDir Path dir) {
        GoalFileController controller = new GoalFileController(dir.toString());

        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> controller.goalFile("plan-missing.md", null)));
    }

    private static HttpStatus statusOf(ThrowingCall call) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, call::run);
        return HttpStatus.valueOf(error.getStatusCode().value());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
