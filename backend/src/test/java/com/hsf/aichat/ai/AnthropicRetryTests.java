package com.hsf.aichat.ai;

import com.hsf.aichat.FakeAiServer;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The Claude provider retries at most once, so on a rate limit the app switches to another AI quickly instead of
 * waiting for the SDK's retries. The fake AI server plays the Anthropic API; no Spring, no internet.
 */
class AnthropicRetryTests {

    static FakeAiServer ai;

    @BeforeAll
    static void startFakeAi() throws IOException {
        ai = new FakeAiServer();
    }

    @AfterAll
    static void stopFakeAi() {
        ai.stop();
    }

    @Test
    void aRateLimitIsRetriedOnceThenReported() {
        ProviderConfig claude = new ProviderConfig("Claude test", ProviderType.ANTHROPIC, ai.url("limited"),
                "claude-test", null, "test-key");

        AiException e = catchThrowableOfType(AiException.class,
                () -> new AnthropicProvider().chat(claude, "You are a test.", List.of(new AiMessage("user", "Hello"))));

        assertThat(e.getKind()).isEqualTo(AiException.Kind.RATE_LIMIT);
        assertThat(e.getHttpStatus()).isEqualTo(429);
        assertThat(ai.hits("/limited/v1/messages")).isEqualTo(2); // the first try + 1 retry
    }
}
