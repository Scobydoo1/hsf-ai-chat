package com.hsf.aichat.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions format: POST {baseUrl}/chat/completions and POST {baseUrl}/embeddings.
 * The same code works for OpenAI, Groq, OpenRouter, DeepSeek, Ollama and LM Studio: only baseUrl, model and key change.
 */
@Component
public class OpenAiCompatibleProvider implements ChatProvider {

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final RestClient http = HttpSupport.restClient();

    @Override
    public ProviderType type() {
        return ProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public String chat(ProviderConfig config, String systemPrompt, List<AiMessage> messages) {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(Map.of("role", "system", "content", systemPrompt));
        for (AiMessage m : messages) {
            list.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", list);

        JsonNode json = post(config, "/chat/completions", body);
        JsonNode content = json.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new AiException("The AI returned no text: " + json);
        }
        return content.asText();
    }

    @Override
    public boolean supportsEmbeddings() {
        return true;
    }

    @Override
    public List<float[]> embed(ProviderConfig config, List<String> texts) {
        List<float[]> result = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += 64) { // send in batches
            List<String> batch = texts.subList(start, Math.min(start + 64, texts.size()));
            JsonNode json = post(config, "/embeddings", Map.of("model", config.getEmbeddingModel(), "input", batch));
            float[][] ordered = new float[batch.size()][];
            for (JsonNode item : json.path("data")) {
                ordered[item.path("index").asInt()] = HttpSupport.toFloats(item.path("embedding"));
            }
            result.addAll(List.of(ordered));
        }
        return result;
    }

    private JsonNode post(ProviderConfig config, String path, Object body) {
        String url = HttpSupport.trimSlash(HttpSupport.orDefault(config.getBaseUrl(), DEFAULT_BASE_URL)) + path;
        try {
            return http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                            h.setBearerAuth(config.getApiKey()); // Ollama / LM Studio need no key
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw HttpSupport.toAiException(config.getName(), url, e);
        }
    }
}
