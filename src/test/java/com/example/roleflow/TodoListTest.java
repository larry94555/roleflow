package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoListTest {

    @Test
    void readsTheIssueLabelOnEachLine() {
        List<TodoItem> items = TodoList.parse("ambiguous: pick a language\ntoo high level: build the app");

        assertEquals(2, items.size());
        assertEquals(new TodoItem("pick a language", TodoItem.AMBIGUOUS), items.get(0));
        assertEquals(new TodoItem("build the app", TodoItem.TOO_HIGH_LEVEL), items.get(1));
    }

    @Test
    void recognizesTooHighLevelVariants() {
        assertEquals(TodoItem.TOO_HIGH_LEVEL, TodoList.parse("too-high-level: x").get(0).issue());
        assertEquals(TodoItem.TOO_HIGH_LEVEL, TodoList.parse("Too High Level: x").get(0).issue());
    }

    @Test
    void defaultsToAmbiguousWhenNoLabelIsPresent() {
        List<TodoItem> items = TodoList.parse("- install the tools\n- verify the result");
        assertEquals(2, items.size());
        assertEquals("install the tools", items.get(0).step());
        assertEquals(TodoItem.AMBIGUOUS, items.get(0).issue());
    }

    @Test
    void keepsAColonThatIsJustSentencePunctuationInTheStep() {
        // A long left side before the colon is sentence text, not an issue label, so keep the whole line.
        List<TodoItem> items = TodoList.parse("Install the database server: then configure it for the app");
        assertEquals(1, items.size());
        assertTrue(items.get(0).step().startsWith("Install the database server:"), items.get(0).step());
    }

    @Test
    void skipsBlankLinesAndATodoListHeader() {
        List<TodoItem> items = TodoList.parse("TODO_LIST:\n\nambiguous: do the thing\n");
        assertEquals(1, items.size());
        assertEquals("do the thing", items.get(0).step());
    }

    @Test
    void capsTheNumberOfItems() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) text.append("ambiguous: step ").append(i).append('\n');
        assertEquals(TodoList.MAX_ITEMS, TodoList.parse(text.toString()).size());
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertTrue(TodoList.parse(null).isEmpty());
        assertTrue(TodoList.parse("   \n  ").isEmpty());
    }
}
