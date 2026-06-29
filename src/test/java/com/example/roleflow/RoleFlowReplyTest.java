package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleFlowReplyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesWellFormedJson() {
        RoleFlowReply reply = RoleFlowReply.parse(
                "{\"message\":\"hi\",\"decision\":\"signal\",\"artifact\":\"\"}", mapper);

        assertEquals("hi", reply.message());
        assertEquals("signal", reply.decision());
        assertEquals("", reply.artifact());
    }

    @Test
    void extractsJsonEmbeddedInProse() {
        RoleFlowReply reply = RoleFlowReply.parse(
                "Sure! Here is the result:\n{\"message\":\"ok\",\"decision\":\"request\"}\nThanks.", mapper);

        assertEquals("ok", reply.message());
        assertEquals("request", reply.decision());
    }

    @Test
    void missingFieldsDefaultToEmpty() {
        RoleFlowReply reply = RoleFlowReply.parse("{\"message\":\"only message\"}", mapper);

        assertEquals("only message", reply.message());
        assertEquals("", reply.decision());
        assertEquals("", reply.artifact());
    }

    @Test
    void fallsBackToPlainTextWhenNoJson() {
        RoleFlowReply reply = RoleFlowReply.parse("just some text", mapper);

        assertEquals("just some text", reply.message());
        assertEquals("", reply.decision());
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("", RoleFlowReply.parse(null, mapper).message());
        assertEquals("", RoleFlowReply.parse("   ", mapper).message());
    }

    @Test
    void parsesJsonWithLiteralNewlinesInStringValues() {
        // Smaller models format the message/artifact with real line breaks instead of \n; strict JSON
        // would reject this, but the parser tolerates it so the decision is still read correctly.
        String raw = "{\"message\": \"Line one\nLine two\", \"decision\": \"continue\", \"artifact\": \"\"}";

        RoleFlowReply reply = RoleFlowReply.parse(raw, mapper);

        assertEquals("continue", reply.decision(), "decision must be read despite the literal newline");
        assertEquals("Line one\nLine two", reply.message());
    }

    @Test
    void parsesAReplyWithInvalidLatexBackslashEscapesInAString() {
        // Models often put LaTeX like "\( n^2 \)" in the message. "\(" is an invalid JSON escape, which would
        // make strict parsing fail and lose the decision; the parser must tolerate it so the decision stands.
        String raw = "{\"message\": \"There is a prime between \\( n^2 \\) and \\( (n+1)^2 \\). OK?\", "
                + "\"decision\": \"unclear\", \"artifact\": \"\"}";

        RoleFlowReply reply = RoleFlowReply.parse(raw, mapper);

        assertEquals("unclear", reply.decision(), "the decision must survive invalid LaTeX escapes");
        assertTrue(reply.message().contains("n^2"), reply.message());
    }

    @Test
    void keepsTheFirstValueWhenAKeyIsDuplicated() {
        // Smaller models sometimes emit a key twice — the real content first, then an empty template echo
        // (e.g. "artifact":"...plan...","artifact":""). Keeping the first occurrence preserves the content.
        String raw = "{\"message\":\"plan built\",\"decision\":\"continue\","
                + "\"artifact\":\"## Phase 1\",\"decision\":\"continue\",\"artifact\":\"\"}";

        RoleFlowReply reply = RoleFlowReply.parse(raw, mapper);

        assertEquals("continue", reply.decision());
        assertEquals("## Phase 1", reply.artifact(), "the first, non-empty artifact must win");
    }
}
