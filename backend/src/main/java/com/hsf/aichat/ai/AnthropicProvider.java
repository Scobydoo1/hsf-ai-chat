package com.hsf.aichat.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Anthropic Claude, called with the official Anthropic Java SDK (com.anthropic:anthropic-java).
 * Anthropic has no embeddings API, so for RAG pick another provider for embeddings
 * (Gemini, OpenAI, Ollama or the Demo provider) and keep Claude for the answers.
 */
@Component
public class AnthropicProvider implements ChatProvider {

    /** Models that accept server-side refusal fallbacks ("fallbacks": "default"). */
    private static final Set<String> FALLBACK_MODELS =
            Set.of("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5");

    private final Map<String, AnthropicClient> clients = new ConcurrentHashMap<>();

    @Override
    public ProviderType type() {
        return ProviderType.ANTHROPIC;
    }

    @Override
    public String chat(ProviderConfig config, String systemPrompt, List<AiMessage> messages) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new AiException(config.getName() + ": API key is missing", null, AiException.Kind.AUTH, null, null);
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(config.getModel())
                .maxTokens(16000L)
                .system(systemPrompt);

        boolean firstParty = config.getBaseUrl() == null || config.getBaseUrl().isBlank();
        if (firstParty && FALLBACK_MODELS.contains(config.getModel())) {
            // If a safety classifier declines the request, the server retries it on a suitable model
            params.addBeta(AnthropicBeta.of("server-side-fallback-2026-07-01"));
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        for (AiMessage m : messages) {
            if (ChatMessageRoles.isAssistant(m.role())) {
                params.addAssistantMessage(m.content());
            } else {
                params.addUserMessage(m.content());
            }
        }

        BetaMessage response;
        try {
            response = client(config).beta().messages().create(params.build());
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            String hint = switch (status) {
                case 401, 403 -> " (check the API key)";
                case 404 -> " (check the model name)";
                case 429 -> " (rate limit reached, try again later)";
                default -> "";
            };
            // 429 = rate limit, 529 "overloaded" and other 5xx = unavailable (same rules as the other platforms)
            String retryAfter = e.headers().values("retry-after").stream().findFirst().orElse(null);
            throw new AiException(config.getName() + " returned HTTP " + status + hint + ": " + e.getMessage(), e,
                    HttpSupport.kindOf(status), status, HttpSupport.retryAfter(retryAfter, null));
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            // could not connect, or the request timed out
            throw new AiException(config.getName() + " request failed: " + e.getMessage(), e,
                    AiException.Kind.UNAVAILABLE, null, null);
        } catch (Exception e) {
            throw new AiException(config.getName() + " request failed: " + e.getMessage(), e);
        }

        if (response.stopReason().map(r -> r.equals(BetaStopReason.REFUSAL)).orElse(false)) {
            throw new AiException("Claude declined to answer this request. Try rephrasing it.");
        }
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new AiException("Claude returned no text (stop reason: " + response.stopReason().orElse(null) + ")");
        }
        return text;
    }

    private AnthropicClient client(ProviderConfig config) {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        return clients.computeIfAbsent(base + "|" + config.getApiKey(), k -> {
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                    .apiKey(config.getApiKey())
                    .maxRetries(1); // retry once at most (the SDK default is 2): on a limit the app switches AI instead
            if (!base.isEmpty()) {
                builder.baseUrl(base);
            }
            return builder.build();
        });
    }
}
