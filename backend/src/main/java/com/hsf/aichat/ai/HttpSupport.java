package com.hsf.aichat.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsf.aichat.ai.AiException.Kind;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared HTTP setup for the providers that call REST APIs directly. */
final class HttpSupport {

    /** Gemini says how long to wait inside the error body: "retryDelay": "37s" */
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"");

    private HttpSupport() {
    }

    static RestClient restClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(120)); // long answers can take a while, but do not wait forever
        return RestClient.builder().requestFactory(factory).build();
    }

    static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Turn an HTTP error from a platform into a short message the user can act on. */
    static AiException toAiException(String platform, String url, Exception e) {
        if (e instanceof RestClientResponseException r) {
            int status = r.getStatusCode().value();
            String fullBody = r.getResponseBodyAsString();
            String body = fullBody.length() > 400 ? fullBody.substring(0, 400) + "..." : fullBody;
            String hint = switch (status) {
                case 401, 403 -> " (check the API key)";
                case 404 -> " (check the model name and base URL)";
                case 429 -> " (rate limit or quota reached, try again later)";
                default -> "";
            };
            HttpHeaders headers = r.getResponseHeaders();
            String retryAfterHeader = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            return new AiException(platform + " returned HTTP " + status + hint + ": " + body, e,
                    kindOf(status), status, retryAfter(retryAfterHeader, fullBody));
        }
        if (e instanceof ResourceAccessException) {
            return new AiException(platform + ": could not connect to " + url
                    + ". Check the base URL, your internet connection, or that the local server (e.g. Ollama) is running.",
                    e, Kind.UNAVAILABLE, null, null);
        }
        return new AiException(platform + " request failed: " + e.getMessage(), e);
    }

    /** 429 = rate limit, 5xx = platform down (also Anthropic's 529 "overloaded"), 401/403 = key, 404 = model/URL */
    static Kind kindOf(int status) {
        if (status == 429) {
            return Kind.RATE_LIMIT;
        }
        if (status >= 500 && status <= 599) {
            return Kind.UNAVAILABLE;
        }
        if (status == 401 || status == 403) {
            return Kind.AUTH;
        }
        return status == 404 ? Kind.NOT_FOUND : Kind.AI_ERROR;
    }

    /**
     * How long the platform asked us to wait: the Retry-After header in seconds (Groq, OpenAI),
     * else Gemini's "retryDelay" in the error body, else null.
     */
    static Duration retryAfter(String retryAfterHeader, String body) {
        Duration fromHeader = seconds(retryAfterHeader);
        if (fromHeader != null) {
            return fromHeader;
        }
        Matcher m = RETRY_DELAY.matcher(body == null ? "" : body);
        return m.find() ? seconds(m.group(1)) : null;
    }

    private static Duration seconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofMillis(Math.round(Double.parseDouble(value.trim()) * 1000));
        } catch (NumberFormatException e) {
            return null; // e.g. an HTTP date instead of seconds: ignore it
        }
    }

    static float[] toFloats(JsonNode array) {
        float[] v = new float[array.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) array.get(i).asDouble();
        }
        return v;
    }
}
