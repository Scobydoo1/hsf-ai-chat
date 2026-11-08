package com.hsf.aichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny fake AI platform for the tests, built on the JDK's own HTTP server (no extra library, no internet).
 * It speaks the OpenAI format, so an OPENAI_COMPATIBLE provider can use url("ok") etc. as its base URL:
 * <pre>
 *   /ok/chat/completions        200 with an answer
 *   /limited/chat/completions   429 + "Retry-After: 30" (rate limit reached)
 *   /down/chat/completions      503 (platform down)
 *   /ok/embeddings              200 with word-hash vectors (texts with the same words get similar vectors)
 *   /flaky/embeddings           like /ok/embeddings, or 429 after failEmbeddings(true)
 *   /slow/embeddings            like /ok/embeddings, but after holdEmbeddings(true) it waits until holdEmbeddings(false)
 *   /limited/v1/messages        429 in the Anthropic format (for the Claude provider, which uses the Anthropic SDK)
 * </pre>
 * Every request is counted per path, so a test can check whether a provider was called,
 * and the last request body is kept, so a test can check what the app sent.
 */
public class FakeAiServer {

    private static final int DIMENSIONS = 64;

    private final HttpServer server;
    private final ExecutorService threads = Executors.newFixedThreadPool(4);
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<String, String> lastRequests = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean embeddingsFail;
    /** /slow/embeddings waits until this is opened (count 0 = open) */
    private volatile CountDownLatch slowGate = new CountDownLatch(0);

    public FakeAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // port 0 = any free port
        server.createContext("/", this::handle);
        server.setExecutor(threads);
        server.start();
    }

    /** Base URL for a provider, e.g. url("limited") = http://127.0.0.1:54321/limited */
    public String url(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + prefix;
    }

    /** How many requests reached this path, e.g. hits("/limited/chat/completions") */
    public int hits(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    /** The body of the last request to this path, e.g. what the app sent to /ok/chat/completions */
    public JsonNode lastRequest(String path) throws IOException {
        String body = lastRequests.get(path);
        return body == null ? null : json.readTree(body);
    }

    /** true = /flaky/embeddings answers 429 from now on */
    public void failEmbeddings(boolean fail) {
        embeddingsFail = fail;
    }

    /** true = /slow/embeddings waits (up to 10 s) before it answers; false = let it answer now */
    public void holdEmbeddings(boolean hold) {
        if (hold) {
            slowGate = new CountDownLatch(1);
        } else {
            slowGate.countDown();
        }
    }

    public void stop() {
        server.stop(0);
        threads.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        try {
            byte[] request = exchange.getRequestBody().readAllBytes();
            lastRequests.put(path, new String(request, StandardCharsets.UTF_8));
            switch (path) {
                case "/ok/chat/completions" -> respond(exchange, 200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Fake answer\"}}]}");
                case "/limited/chat/completions" -> {
                    exchange.getResponseHeaders().add("Retry-After", "30");
                    respond(exchange, 429, "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"tokens\"}}");
                }
                case "/down/chat/completions" -> respond(exchange, 503, "{\"error\":{\"message\":\"Service unavailable\"}}");
                case "/ok/embeddings" -> respond(exchange, 200, embeddings(request));
                case "/flaky/embeddings" -> {
                    if (embeddingsFail) {
                        exchange.getResponseHeaders().add("Retry-After", "20");
                        respond(exchange, 429, "{\"error\":{\"message\":\"Embedding quota reached\"}}");
                    } else {
                        respond(exchange, 200, embeddings(request));
                    }
                }
                case "/slow/embeddings" -> {
                    slowGate.await(10, TimeUnit.SECONDS); // a slow platform: answers when the test lets it
                    respond(exchange, 200, embeddings(request));
                }
                // no Retry-After header on purpose: the Anthropic SDK would wait that long before its retry
                case "/limited/v1/messages" -> respond(exchange, 429,
                        "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Rate limit reached\"}}");
                default -> respond(exchange, 404, "{\"error\":{\"message\":\"Unknown path\"}}");
            }
        } catch (Exception e) {
            respond(exchange, 500, "{\"error\":{\"message\":\"Fake server error\"}}"); // never leave the client waiting
        }
    }

    /** OpenAI embeddings answer: {"data":[{"index":0,"embedding":[...]}, ...]}, one vector per input text */
    private String embeddings(byte[] request) throws IOException {
        JsonNode input = json.readTree(request).path("input");
        ObjectNode answer = json.createObjectNode();
        ArrayNode data = answer.putArray("data");
        for (int i = 0; i < input.size(); i++) {
            ObjectNode item = data.addObject();
            item.put("index", i);
            ArrayNode vector = item.putArray("embedding");
            for (float value : wordHash(input.get(i).asText())) {
                vector.add(value);
            }
        }
        return answer.toString();
    }

    /** Like the Demo provider: every word adds 1 to one of 64 slots */
    private static float[] wordHash(String text) {
        float[] v = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 2) {
                v[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        return v;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
