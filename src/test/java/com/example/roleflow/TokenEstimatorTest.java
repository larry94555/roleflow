package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    @Test
    void estimatesTextByCharsPerToken() {
        TokenEstimator estimator = new TokenEstimator(4);

        assertEquals(0, estimator.estimate(""));
        assertEquals(0, estimator.estimate((String) null));
        assertEquals(1, estimator.estimate("abc"));   // ceil(3/4)
        assertEquals(1, estimator.estimate("abcd"));  // ceil(4/4)
        assertEquals(2, estimator.estimate("abcde")); // ceil(5/4)
    }

    @Test
    void estimateMessagesAddsPerMessageOverhead() {
        TokenEstimator estimator = new TokenEstimator(4);

        // role "user" = 1 token, content "abcd" = 1 token, + overhead 4 = 6 tokens.
        int single = estimator.estimate(List.of(Message.user("abcd")));
        assertEquals(6, single);

        int two = estimator.estimate(List.of(Message.user("abcd"), Message.assistant("abcd")));
        // "assistant" = ceil(9/4) = 3 tokens, content 1, overhead 4 = 8; plus the 6 above.
        assertEquals(14, two);
    }

    @Test
    void charsPerTokenIsClampedToAtLeastOne() {
        TokenEstimator estimator = new TokenEstimator(0);

        assertEquals(4, estimator.estimate("abcd"));
    }
}
