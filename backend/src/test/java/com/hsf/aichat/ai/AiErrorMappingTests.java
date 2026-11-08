package com.hsf.aichat.ai;

import com.hsf.aichat.ai.AiException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** How HTTP errors from the AI platforms become AiException kinds (HttpSupport). No network needed. */
class AiErrorMappingTests {

    private static final String URL = "https://api.example.com/v1/chat/completions";

    @Test
    void rateLimitReadsTheRetryAfterHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");
        AiException e = HttpSupport.toAiException("Groq", URL, HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, "{}".getBytes(UTF_8), UTF_8));

        assertThat(e.getKind()).isEqualTo(Kind.RATE_LIMIT);
        assertThat(e.getHttpStatus()).isEqualTo(429);
        assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(e.getMessage()).startsWith("Groq returned HTTP 429 (rate limit or quota reached, try again later)");
        assertThat(e.shortReason()).isEqualTo("rate limit reached (HTTP 429)");
    }

    @Test
    void geminiRetryDelayIsReadFromTheErrorBody() {
        // shortened Gemini 429 body; the retryDelay sits far after the first 400 characters in real answers
        String body = """
                {"error": {"code": 429, "message": "You exceeded your current quota.", "status": "RESOURCE_EXHAUSTED",
                  "details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "37s"}]}}""";
        AiException e = HttpSupport.toAiException("Google Gemini", URL, HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", new HttpHeaders(), body.getBytes(UTF_8), UTF_8));

        assertThat(e.getKind()).isEqualTo(Kind.RATE_LIMIT);
        assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(37));
        assertThat(HttpSupport.retryAfter(null, "{\"retryDelay\": \"2.5s\"}")).isEqualTo(Duration.ofMillis(2500));
        assertThat(HttpSupport.retryAfter("not-a-number", "{}")).isNull();
    }

    @Test
    void statusCodesDecideTheKind() {
        assertThat(HttpSupport.kindOf(429)).isEqualTo(Kind.RATE_LIMIT);
        assertThat(HttpSupport.kindOf(500)).isEqualTo(Kind.UNAVAILABLE);
        assertThat(HttpSupport.kindOf(503)).isEqualTo(Kind.UNAVAILABLE);
        assertThat(HttpSupport.kindOf(529)).isEqualTo(Kind.UNAVAILABLE); // Anthropic "overloaded"
        assertThat(HttpSupport.kindOf(401)).isEqualTo(Kind.AUTH);
        assertThat(HttpSupport.kindOf(403)).isEqualTo(Kind.AUTH);
        assertThat(HttpSupport.kindOf(404)).isEqualTo(Kind.NOT_FOUND);
        assertThat(HttpSupport.kindOf(400)).isEqualTo(Kind.AI_ERROR);

        AiException e = HttpSupport.toAiException("Groq", URL, HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", new HttpHeaders(), new byte[0], UTF_8));
        assertThat(e.getKind()).isEqualTo(Kind.UNAVAILABLE);
        assertThat(e.getRetryAfter()).isNull();
    }

    @Test
    void connectionProblemsMeanUnavailable() {
        AiException e = HttpSupport.toAiException("Ollama", URL, new ResourceAccessException("Connection refused"));

        assertThat(e.getKind()).isEqualTo(Kind.UNAVAILABLE);
        assertThat(e.getHttpStatus()).isNull();
        assertThat(e.getMessage()).startsWith("Ollama: could not connect to " + URL);
        assertThat(e.shortReason()).isEqualTo("could not connect or timed out");
    }

    @Test
    void theOldConstructorsMeanAiError() {
        assertThat(new AiException("No AI provider is active.").getKind()).isEqualTo(Kind.AI_ERROR);
        AiException withCause = new AiException("failed", new IllegalStateException("boom"));
        assertThat(withCause.getKind()).isEqualTo(Kind.AI_ERROR);
        assertThat(withCause.getHttpStatus()).isNull();
        assertThat(withCause.getRetryAfter()).isNull();
        assertThat(withCause.getProviderName()).isNull();
    }
}
