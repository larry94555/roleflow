package com.example.roleflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the TODO list a model emits (one step per line) into {@link TodoItem}s. Each line may be prefixed
 * with an issue label — "ambiguous: ..." or "too high level: ..." — which sets the item's
 * {@link TodoItem#issue()}; a line with no recognizable label defaults to {@code ambiguous}. Bullet/number
 * prefixes are stripped and a hard cap is applied so a noisy reply cannot explode the downstream work.
 */
final class TodoList {

    private TodoList() {}

    static final int MAX_ITEMS = 10;

    static List<TodoItem> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TodoItem> items = new ArrayList<>();
        for (String raw : text.split("[\\r\\n]+")) {
            String line = raw.strip().replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "").strip();
            if (line.isBlank() || line.matches("(?i)todo[_ ]?list\\s*:?\\s*")) {
                continue; // skip blanks and a bare "TODO_LIST" header line
            }
            TodoItem item = toItem(line);
            if (item != null) {
                items.add(item);
                if (items.size() >= MAX_ITEMS) break;
            }
        }
        return items;
    }

    /** Turns one cleaned line into a {@link TodoItem}, reading an optional "issue: step" prefix. */
    private static TodoItem toItem(String line) {
        String issue = TodoItem.AMBIGUOUS;
        String step = line;
        int colon = line.indexOf(':');
        if (colon > 0 && colon <= 40) {
            String label = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String rest = line.substring(colon + 1).strip();
            if (label.contains("high")) {            // "too high level", "too-high-level"
                issue = TodoItem.TOO_HIGH_LEVEL;
                step = rest;
            } else if (label.contains("ambig")) {    // "ambiguous"
                issue = TodoItem.AMBIGUOUS;
                step = rest;
            }
            // Otherwise the colon is just sentence punctuation; keep the whole line as the step.
        }
        if (step.isBlank()) {
            return null;
        }
        if (step.length() > 200) {
            step = step.substring(0, 200).strip();
        }
        return new TodoItem(step, issue);
    }
}
