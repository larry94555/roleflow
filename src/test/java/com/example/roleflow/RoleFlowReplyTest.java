package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
