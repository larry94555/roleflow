package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicListTest {

    @Test
    void parsesOneTopicPerLine() {
        assertEquals(List.of("mathematics", "programming", "sports"),
                TopicList.parse("mathematics\nprogramming\nsports"));
    }

    @Test
    void stripsBulletNumberAndLabelPrefixes() {
        String text = "- mathematics\n2. programming\n* Topic: sports";
        assertEquals(List.of("mathematics", "programming", "sports"), TopicList.parse(text));
    }

    @Test
    void splitsOnCommasAndSemicolons() {
        assertEquals(List.of("mathematics", "programming", "sports"),
                TopicList.parse("mathematics, programming; sports"));
    }

    @Test
    void stripsSurroundingQuotesAndTrailingPunctuation() {
        assertEquals(List.of("mathematics", "programming"),
                TopicList.parse("\"mathematics\".\n'programming'"));
    }

    @Test
    void deDuplicatesWhilePreservingOrder() {
        assertEquals(List.of("mathematics", "programming"),
                TopicList.parse("mathematics\nprogramming\nmathematics"));
    }

    @Test
    void capsAtTheMaximumNumberOfTopics() {
        String text = "a\nb\nc\nd\ne\nf\ng";
        List<String> topics = TopicList.parse(text);
        assertEquals(TopicList.MAX_TOPICS, topics.size());
        assertEquals(List.of("a", "b", "c", "d", "e"), topics);
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertTrue(TopicList.parse(null).isEmpty());
        assertTrue(TopicList.parse("   \n  ").isEmpty());
    }

    @Test
    void truncatesAnOverlyLongTopicToSixtyCharacters() {
        String longTopic = "a".repeat(100);
        List<String> topics = TopicList.parse(longTopic);
        assertEquals(1, topics.size());
        assertEquals(60, topics.get(0).length());
    }
}
