package com.hsf.aichat.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared HTTP setup for the providers that call REST APIs directly. */
final class HttpSupport {

    private HttpSupport() {
    }

    static RestClient restClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(180)); // long answers can take a while
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
            String body = r.getResponseBodyAsString();
            if (body.length() > 400) {
                body = body.substring(0, 400) + "...";
            }
            String hint = switch (r.getStatusCode().value()) {
                case 401, 403 -> " (check the API key)";
                case 404 -> " (check the model name and base URL)";
                case 429 -> " (rate limit or quota reached, try again later)";
                default -> "";
            };
            return new AiException(platform + " returned HTTP " + r.getStatusCode().value() + hint + ": " + body, e);
        }
        if (e instanceof org.springframework.web.client.ResourceAccessException) {
            return new AiException(platform + ": could not connect to " + url
                    + ". Check the base URL, your internet connection, or that the local server (e.g. Ollama) is running.", e);
        }
        return new AiException(platform + " request failed: " + e.getMessage(), e);
    }

    static float[] toFloats(JsonNode array) {
        float[] v = new float[array.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) array.get(i).asDouble();
        }
        return v;
    }
}
