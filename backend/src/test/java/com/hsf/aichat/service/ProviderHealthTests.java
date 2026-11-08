package com.hsf.aichat.service;

import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.ai.AiException.Kind;
import com.hsf.aichat.entity.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** How long a provider rests after each kind of error. A test clock moves time forward: no waiting, no Spring. */
class ProviderHealthTests {

    /** A clock that only moves when the test says so */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    final TestClock clock = new TestClock();
    final ProviderHealth health = new ProviderHealth(clock);
    final ProviderConfig groq = provider(7L);

    @Test
    void rateLimitRestsAsLongAsThePlatformAsks() {
        health.failed(groq, error(Kind.RATE_LIMIT, 429, Duration.ofSeconds(37)));
        assertThat(health.isResting(7L)).isTrue();
        assertThat(health.cooldownSeconds(7L)).isEqualTo(37);
        assertThat(health.lastError(7L)).isEqualTo("Rate limit reached (HTTP 429)");
        assertThat(health.restingKind(7L)).isEqualTo(Kind.RATE_LIMIT);

        clock.advance(Duration.ofMillis(36_500));
        assertThat(health.cooldownSeconds(7L)).isEqualTo(1); // 0.5 s left is shown as 1 s (rounded up)

        clock.advance(Duration.ofMillis(500));
        assertThat(health.isResting(7L)).isFalse();
        assertThat(health.cooldownSeconds(7L)).isZero();
        assertThat(health.lastError(7L)).isNull();
    }

    @Test
    void rateLimitRestIsBetweenFiveSecondsAndFifteenMinutes() {
        health.failed(groq, error(Kind.RATE_LIMIT, 429, null));
        assertThat(health.cooldownSeconds(7L)).isEqualTo(60); // the platform did not say how long

        health.failed(groq, error(Kind.RATE_LIMIT, 429, Duration.ofSeconds(1)));
        assertThat(health.cooldownSeconds(7L)).isEqualTo(5);

        health.failed(groq, error(Kind.RATE_LIMIT, 429, Duration.ofHours(2)));
        assertThat(health.cooldownSeconds(7L)).isEqualTo(15 * 60);
    }

    @Test
    void otherErrorsHaveFixedRests() {
        health.failed(groq, error(Kind.UNAVAILABLE, 503, null));
        assertThat(health.cooldownSeconds(7L)).isEqualTo(30);
        assertThat(health.lastError(7L)).isEqualTo("Not responding (HTTP 503)");

        health.failed(groq, error(Kind.AUTH, 401, null));
        assertThat(health.cooldownSeconds(7L)).isEqualTo(120);

        health.failed(groq, error(Kind.NOT_FOUND, 404, null));
        assertThat(health.cooldownSeconds(7L)).isEqualTo(120);

        ProviderConfig other = provider(8L);
        health.failed(other, error(Kind.AI_ERROR, 400, null)); // e.g. a bad request: retrying elsewhere is enough
        assertThat(health.isResting(8L)).isFalse();
    }

    @Test
    void successOrClearEndsTheRest() {
        health.failed(groq, error(Kind.UNAVAILABLE, null, null));
        assertThat(health.lastError(7L)).isEqualTo("Could not connect or timed out");
        health.succeeded(7L);
        assertThat(health.isResting(7L)).isFalse();

        health.failed(groq, error(Kind.RATE_LIMIT, 429, null));
        health.clear(7L);
        assertThat(health.cooldownSeconds(7L)).isZero();

        health.failed(groq, error(Kind.RATE_LIMIT, 429, null));
        health.clearAll();
        assertThat(health.isResting(7L)).isFalse();
    }

    @Test
    void embeddingRestsAreSeparateFromChatRests() {
        health.embeddingFailed(groq, error(Kind.RATE_LIMIT, 429, Duration.ofSeconds(20)));
        assertThat(health.isEmbeddingResting(7L)).isTrue();
        assertThat(health.isResting(7L)).isFalse(); // its chat keeps working
        assertThat(health.cooldownSeconds(7L)).isZero();
        assertThat(health.lastError(7L)).isNull();

        health.failed(groq, error(Kind.UNAVAILABLE, 503, null));
        health.embeddingSucceeded(7L);
        assertThat(health.isEmbeddingResting(7L)).isFalse();
        assertThat(health.isResting(7L)).isTrue(); // an embedding success does not end the chat rest

        health.embeddingFailed(groq, error(Kind.RATE_LIMIT, 429, Duration.ofSeconds(20)));
        health.clear(7L); // e.g. the provider was edited: both rests end
        assertThat(health.isEmbeddingResting(7L)).isFalse();
        assertThat(health.isResting(7L)).isFalse();

        health.embeddingFailed(groq, error(Kind.RATE_LIMIT, 429, Duration.ofSeconds(20)));
        clock.advance(Duration.ofSeconds(20));
        assertThat(health.isEmbeddingResting(7L)).isFalse(); // same durations as the chat rests
    }

    private static ProviderConfig provider(Long id) {
        ProviderConfig p = new ProviderConfig();
        p.setId(id);
        p.setName("Provider " + id);
        return p;
    }

    private static AiException error(Kind kind, Integer status, Duration retryAfter) {
        return new AiException("test error", null, kind, status, retryAfter);
    }
}
