package com.hsf.aichat.ai;

import java.time.Duration;

/**
 * Thrown when an AI platform call fails; the message is shown to the user.
 * The kind says what went wrong, so the app can switch to another AI (e.g. on a rate limit)
 * and the front-end can show the right hint.
 */
public class AiException extends RuntimeException {

    /** What went wrong. For HTTP errors the status code decides it (see HttpSupport.kindOf). */
    public enum Kind {
        /** HTTP 429: too many requests, or the free quota is used up */
        RATE_LIMIT,
        /** HTTP 5xx, cannot connect, or timeout: the platform is down or overloaded */
        UNAVAILABLE,
        /** HTTP 401 / 403: wrong or missing API key */
        AUTH,
        /** HTTP 404: wrong model name or base URL */
        NOT_FOUND,
        /** Anything else */
        AI_ERROR
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final Duration retryAfter;
    /** The platform whose failure `kind` describes: the first one tried, i.e. the one the user chose. Set by ChatService. */
    private String providerName;

    public AiException(String message) {
        this(message, null, Kind.AI_ERROR, null, null);
    }

    public AiException(String message, Throwable cause) {
        this(message, cause, Kind.AI_ERROR, null, null);
    }

    public AiException(String message, Throwable cause, Kind kind, Integer httpStatus, Duration retryAfter) {
        super(message, cause);
        this.kind = kind == null ? Kind.AI_ERROR : kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Kind getKind() { return kind; }

    /** The HTTP status the platform answered with; null when there was no answer (e.g. could not connect) */
    public Integer getHttpStatus() { return httpStatus; }

    /** How long the platform asked us to wait (Retry-After header or Gemini's retryDelay); null = it did not say */
    public Duration getRetryAfter() { return retryAfter; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    /** A few words for lists and badges, e.g. "rate limit reached (HTTP 429)" */
    public String shortReason() {
        String http = httpStatus == null ? "" : " (HTTP " + httpStatus + ")";
        return switch (kind) {
            case RATE_LIMIT -> "rate limit reached" + http;
            case UNAVAILABLE -> httpStatus == null ? "could not connect or timed out" : "not responding" + http;
            case AUTH -> httpStatus == null ? "API key missing" : "API key rejected" + http;
            case NOT_FOUND -> "model or URL not found" + http;
            case AI_ERROR -> {
                String message = getMessage() == null ? "unknown error" : getMessage();
                yield message.length() > 200 ? message.substring(0, 200) + "…" : message;
            }
        };
    }
}
