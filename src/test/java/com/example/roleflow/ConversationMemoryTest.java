package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryTest {

    /** Records every summarize call and returns a short, deterministic summary so sizing is predictable. */
    private static class CountingSummarizer implements Summarizer {
        int calls;
        final List<List<Message>> evicted = new ArrayList<>();
        final List<String> previousSummaries = new ArrayList<>();

        @Override
        public String summarize(String previousSummary, List<Message> messages) {
            calls++;
            previousSummaries.add(previousSummary);
            evicted.add(List.copyOf(messages));
            return "S" + calls;
        }
    }

    // 1 char == 1 token keeps the arithmetic easy to reason about in these tests.
    private final TokenEstimator estimator = new TokenEstimator(1);

    @Test
    void doesNotCompactWhenEverythingFits() {
        CountingSummarizer summarizer = new CountingSummarizer();
        ConversationMemory memory = new ConversationMemory(estimator, summarizer, 1000);
        memory.recordExchange("hello", "hi there");

        List<Message> sent = memory.prepareRequest("", "how are you?", 0);

        assertEquals(0, summarizer.calls, "no summarization should happen when within budget");
        // system(none) + 2 prior turns + the new prompt
        assertEquals(3, sent.size());
        assertEquals("user", sent.get(0).role());
        assertEquals("hello", sent.get(0).content());
        assertEquals("how are you?", sent.get(sent.size() - 1).content());
        assertEquals(List.of(Message.user("hello"), Message.assistant("hi there")), memory.turns());
    }

    @Test
    void compactsOldestTurnsAndKeepsRequestWithinBudget() {
        CountingSummarizer summarizer = new CountingSummarizer();
        int budget = 120;
        ConversationMemory memory = new ConversationMemory(estimator, summarizer, budget);

        // Three sizeable exchanges that together blow past the budget.
        for (int i = 0; i < 3; i++) {
            memory.recordExchange("u" + i + "-" + "x".repeat(30), "a" + i + "-" + "y".repeat(30));
        }
        int turnsBefore = memory.turns().size();

        List<Message> sent = memory.prepareRequest("", "next", 0);

        // Compaction ran, leaving a summary and fewer retained turns.
        assertTrue(summarizer.calls >= 1, "compaction should invoke the summarizer");
        assertFalse(memory.summary().isBlank(), "a running summary should be recorded");
        assertTrue(memory.turns().size() < turnsBefore, "old turns should be evicted");

        // The core invariant: memory + prompt fit within the budget.
        assertTrue(estimator.estimate(sent) <= budget,
                "assembled request must fit the budget but was " + estimator.estimate(sent));

        // The summary is surfaced as the leading system message; the newest prompt is last.
        assertEquals("system", sent.get(0).role());
        assertTrue(sent.get(0).content().contains("Summary of earlier conversation:"));
        assertEquals("next", sent.get(sent.size() - 1).content());
    }

    @Test
    void keepsTheMostRecentTurnVerbatimWhenCompacting() {
        ConversationMemory memory = new ConversationMemory(estimator, new CountingSummarizer(), 130);
        memory.recordExchange("first-" + "x".repeat(30), "reply-" + "y".repeat(30));
        memory.recordExchange("latest-question", "latest-answer-" + "z".repeat(20));

        List<Message> sent = memory.prepareRequest("", "follow-up", 0);

        boolean keptLatestAnswer = sent.stream()
                .anyMatch(m -> m.content().contains("latest-answer-"));
        assertTrue(keptLatestAnswer, "the most recent turn should be retained verbatim");
    }

    @Test
    void carriesRunningSummaryForwardAcrossRequests() {
        CountingSummarizer summarizer = new CountingSummarizer();
        ConversationMemory memory = new ConversationMemory(estimator, summarizer, 120);
        for (int i = 0; i < 3; i++) {
            memory.recordExchange("u" + i + "-" + "x".repeat(30), "a" + i + "-" + "y".repeat(30));
        }

        memory.prepareRequest("", "next", 0);
        String firstSummary = memory.summary();
        assertFalse(firstSummary.isBlank());

        // A later compaction should fold the prior summary in (it is passed back to the summarizer).
        for (int i = 0; i < 3; i++) {
            memory.recordExchange("v" + i + "-" + "x".repeat(30), "b" + i + "-" + "y".repeat(30));
        }
        memory.prepareRequest("", "again", 0);

        assertTrue(summarizer.previousSummaries.contains(firstSummary),
                "a later compaction must receive the earlier summary to fold it forward");
    }

    @Test
    void clearsMemoryWhenThePromptAloneExceedsTheBudget() {
        ConversationMemory memory = new ConversationMemory(estimator, new CountingSummarizer(), 120);
        memory.recordExchange("hi", "hello");

        List<Message> sent = memory.prepareRequest("", "q-" + "z".repeat(500), 0);

        assertTrue(memory.turns().isEmpty(), "oversized prompt should clear retained turns");
        assertTrue(memory.summary().isBlank(), "oversized prompt should clear the summary");
        // Best-effort: the prompt is still sent even though it cannot fit.
        assertEquals("user", sent.get(sent.size() - 1).role());
    }

    @Test
    void reservesSpaceForTheResponse() {
        CountingSummarizer summarizer = new CountingSummarizer();
        // Budget large enough for history, but the response reserve eats most of it -> must compact.
        ConversationMemory memory = new ConversationMemory(estimator, summarizer, 200);
        for (int i = 0; i < 2; i++) {
            memory.recordExchange("u" + i + "-" + "x".repeat(30), "a" + i + "-" + "y".repeat(30));
        }

        List<Message> withoutReserve = memory.prepareRequest("", "next", 0);
        int sizeNoReserve = estimator.estimate(withoutReserve);

        // Rebuild and compact harder with a big reserve.
        ConversationMemory memory2 = new ConversationMemory(estimator, new CountingSummarizer(), 200);
        for (int i = 0; i < 2; i++) {
            memory2.recordExchange("u" + i + "-" + "x".repeat(30), "a" + i + "-" + "y".repeat(30));
        }
        List<Message> withReserve = memory2.prepareRequest("", "next", 150);

        assertTrue(estimator.estimate(withReserve) <= 200 - 150,
                "request must fit budget minus the reserved response space");
        assertTrue(estimator.estimate(withReserve) <= sizeNoReserve);
    }
}
