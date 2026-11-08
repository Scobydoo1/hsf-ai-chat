package com.hsf.aichat.service;

import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.entity.ProviderConfig;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which AI providers are "resting" after an error, so the automatic switch does not waste
 * a slow call on them. Kept in memory only (a restart forgets it) and safe to use from many requests at once.
 * How long a provider rests:
 * - rate limit (HTTP 429): the wait the platform asked for, at least 5 s and at most 15 min (60 s if it did not say);
 * - not responding (5xx, timeout): 30 s;
 * - wrong API key or model not found: 2 min (until someone fixes it in Settings, which also ends the rest);
 * - other errors: no rest.
 * Chat and embeddings rest separately (same durations): each model has its own limits, so a busy embedding
 * service must not stop the chat. The provider list (cooldownSeconds, lastError) shows the chat rest only.
 */
@Component
public class ProviderHealth {

    private static final Duration MIN_RATE_LIMIT_REST = Duration.ofSeconds(5);
    private static final Duration MAX_RATE_LIMIT_REST = Duration.ofMinutes(15);
    private static final Duration DEFAULT_RATE_LIMIT_REST = Duration.ofSeconds(60);
    private static final Duration UNAVAILABLE_REST = Duration.ofSeconds(30);
    private static final Duration SETTINGS_ERROR_REST = Duration.ofSeconds(120);

    /** Until when a provider rests, and why */
    private record Rest(Instant until, AiException.Kind kind, String reason) {
    }

    private final Map<Long, Rest> chatRests = new ConcurrentHashMap<>();
    private final Map<Long, Rest> embeddingRests = new ConcurrentHashMap<>();
    private final Clock clock;

    public ProviderHealth() {
        this(Clock.systemUTC());
    }

    /** Tests pass their own clock, so they can move time forward */
    ProviderHealth(Clock clock) {
        this.clock = clock;
    }

    /** A chat call to this provider failed: let it rest for a while (how long depends on the kind of error). */
    public void failed(ProviderConfig provider, AiException e) {
        rest(chatRests, provider, e);
    }

    /** A chat call worked: the provider is healthy again. */
    public void succeeded(Long id) {
        if (id != null) {
            chatRests.remove(id);
        }
    }

    /** Embedding a question or a document failed: only the embeddings rest, the chat keeps working. */
    public void embeddingFailed(ProviderConfig provider, AiException e) {
        rest(embeddingRests, provider, e);
    }

    /** An embedding call worked. */
    public void embeddingSucceeded(Long id) {
        if (id != null) {
            embeddingRests.remove(id);
        }
    }

    /** While true, the document search skips vector search and uses keywords (no slow call that would fail). */
    public boolean isEmbeddingResting(Long id) {
        return current(embeddingRests, id, clock.instant()) != null;
    }

    /** Forget both rests (chat and embeddings), e.g. after the provider was edited or activated in Settings. */
    public void clear(Long id) {
        if (id != null) {
            chatRests.remove(id);
            embeddingRests.remove(id);
        }
    }

    /** Forget everything (handy for tests). */
    public void clearAll() {
        chatRests.clear();
        embeddingRests.clear();
    }

    /** Seconds until the automatic switch uses this provider for chat again, rounded up; 0 = healthy. */
    public int cooldownSeconds(Long id) {
        Instant now = clock.instant();
        Rest rest = current(chatRests, id, now);
        if (rest == null) {
            return 0;
        }
        long millis = Duration.between(now, rest.until()).toMillis();
        return (int) Math.max(1, (millis + 999) / 1000);
    }

    public boolean isResting(Long id) {
        return current(chatRests, id, clock.instant()) != null;
    }

    /** Short reason while resting, e.g. "Rate limit reached (HTTP 429)"; null when healthy. */
    public String lastError(Long id) {
        Rest rest = current(chatRests, id, clock.instant());
        return rest == null ? null : rest.reason();
    }

    /** What kind of error made it rest (for the notice text); null when healthy. */
    public AiException.Kind restingKind(Long id) {
        Rest rest = current(chatRests, id, clock.instant());
        return rest == null ? null : rest.kind();
    }

    private void rest(Map<Long, Rest> rests, ProviderConfig provider, AiException e) {
        Duration rest = restFor(e);
        if (provider.getId() != null && rest.isPositive()) {
            String reason = e.shortReason();
            reason = Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
            rests.put(provider.getId(), new Rest(clock.instant().plus(rest), e.getKind(), reason));
        }
    }

    /** The rest of this provider, or null when it has none or it is over (then it is forgotten). */
    private static Rest current(Map<Long, Rest> rests, Long id, Instant now) {
        Rest rest = id == null ? null : rests.get(id);
        if (rest != null && !now.isBefore(rest.until())) {
            rests.remove(id, rest);
            return null;
        }
        return rest;
    }

    static Duration restFor(AiException e) {
        return switch (e.getKind()) {
            case RATE_LIMIT -> clamp(e.getRetryAfter() == null ? DEFAULT_RATE_LIMIT_REST : e.getRetryAfter());
            case UNAVAILABLE -> UNAVAILABLE_REST;
            case AUTH, NOT_FOUND -> SETTINGS_ERROR_REST;
            case AI_ERROR -> Duration.ZERO;
        };
    }

    private static Duration clamp(Duration wait) {
        if (wait.compareTo(MIN_RATE_LIMIT_REST) < 0) {
            return MIN_RATE_LIMIT_REST;
        }
        return wait.compareTo(MAX_RATE_LIMIT_REST) > 0 ? MAX_RATE_LIMIT_REST : wait;
    }
}
