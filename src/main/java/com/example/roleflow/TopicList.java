package com.example.roleflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the topics a model emits (one per line, or comma/semicolon separated) into a clean, de-duplicated
 * list. Bullet/number prefixes are stripped and a hard cap is applied so a noisy reply cannot explode the
 * downstream web searches.
 */
final class TopicList {

    private TopicList() {}

    static final int MAX_TOPICS = 5;

    static List<String> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> topics = new LinkedHashSet<>();
        for (String raw : text.split("[\\r\\n,;]+")) {
            String topic = clean(raw);
            if (!topic.isBlank()) {
                topics.add(topic);
                if (topics.size() >= MAX_TOPICS) break;
            }
        }
        return new ArrayList<>(topics);
    }

    private static String clean(String raw) {
        String topic = raw.strip()
                .replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "")   // bullet / number prefix
                .replaceFirst("(?i)^topics?\\s*:\\s*", "")        // a leading "Topic:" label
                .strip();
        // Drop trailing punctuation and surrounding quotes/brackets.
        topic = topic.replaceAll("^[\"'\\[(]+", "").replaceAll("[\"'\\]).]+$", "").strip();
        return topic.length() > 60 ? topic.substring(0, 60).strip() : topic;
    }
}
