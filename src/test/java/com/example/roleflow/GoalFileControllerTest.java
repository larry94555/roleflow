package com.example.roleflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalFileControllerTest {

    @Test
    void servesAnExistingGoalFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("goal-run1.md"), "# Goal\nDo it.");
        GoalFileController controller = new GoalFileController(dir.toString());

        ResponseEntity<Resource> response = controller.goalFile("goal-run1.md");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().exists());
        assertEquals("# Goal\nDo it.",
                new String(response.getBody().getInputStream().readAllBytes()));
    }

    @Test
    void rejectsNamesThatAreNotSimpleMarkdownFiles(@TempDir Path dir) {
        GoalFileController controller = new GoalFileController(dir.toString());

        // Wrong extension and path-traversal attempts are rejected before touching the filesystem.
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.goalFile("notes.txt")));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.goalFile("../secret.md")));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.goalFile("a/b.md")));
    }

    @Test
    void unknownFileYieldsNotFound(@TempDir Path dir) {
        GoalFileController controller = new GoalFileController(dir.toString());

        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> controller.goalFile("plan-missing.md")));
    }

    private static HttpStatus statusOf(Runnable call) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, call::run);
        return HttpStatus.valueOf(error.getStatusCode().value());
    }
}
