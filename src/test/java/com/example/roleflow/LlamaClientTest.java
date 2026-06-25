package com.example.roleflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class LlamaClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void requestBodyOmitsTemperatureWhenNull() {
        Map<String, Object> body = LlamaClient.requestBody(
                "m", List.of(Map.of("role", "user", "content", "hi")), 128, true, null);

        assertEquals("m", body.get("model"));
        assertEquals(128, body.get("max_tokens"));
        assertEquals(true, body.get("cache_prompt"));
        assertEquals(false, body.get("stream"));
        assertFalse(body.containsKey("temperature"));
    }

    @Test
    void requestBodyIncludesTemperatureWhenProvided() {
        Map<String, Object> body = LlamaClient.requestBody(
                "m", List.of(), 128, false, 0.7);

        assertEquals(0.7, body.get("temperature"));
    }

    @Test
    void askThrowsOnBlankPrompt() {
        LlamaClient client = new LlamaClient(mapper);

        assertThrows(IllegalArgumentException.class, () -> client.ask("sys", "  ", null, null));
    }

    @Test
    void askSendsSystemAndUserMessagesAndParsesReply() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        startStubServer(received, "Hello from the model");

        LlamaClient client = clientPointingAtStub();
        String reply = client.ask("You are helpful", "What is 2+2?", 99, 0.3);

        assertEquals("Hello from the model", reply);

        JsonNode body = received.get();
        JsonNode messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("You are helpful", messages.get(0).path("content").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        assertEquals("What is 2+2?", messages.get(1).path("content").asText());
        assertEquals(99, body.path("max_tokens").asInt());
        assertEquals(0.3, body.path("temperature").asDouble(), 1e-9);
    }

    @Test
    void askOmitsSystemMessageWhenBlank() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        startStubServer(received, "ok");

        LlamaClient client = clientPointingAtStub();
        client.ask("   ", "ping", null, null);

        JsonNode messages = received.get().path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertNull(received.get().get("temperature"));
    }

    @Test
    void askThrowsOnNonSuccessStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] out = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();

        LlamaClient client = clientPointingAtStub();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.ask(null, "hi", null, null));
        assertTrue(error.getMessage().contains("500"));
    }

    private void startStubServer(AtomicReference<JsonNode> received, String reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            Map<String, Object> response = Map.of("choices",
                    List.of(Map.of("message", Map.of("role", "assistant", "content", reply))));
            byte[] out = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    private LlamaClient clientPointingAtStub() {
        LlamaClient client = new LlamaClient(mapper);
        setField(client, "host", "127.0.0.1");
        setField(client, "port", server.getAddress().getPort());
        setField(client, "model", "test-model");
        return client;
    }
}
