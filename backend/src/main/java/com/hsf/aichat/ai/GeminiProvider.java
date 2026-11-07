package com.hsf.aichat.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini API.
 * Chat:       POST {base}/models/{model}:generateContent
 * Embeddings: POST {base}/models/{model}:batchEmbedContents
 * The key goes in the x-goog-api-key header. Note Gemini calls the assistant role "model".
 */
@Component
public class GeminiProvider implements ChatProvider {

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final RestClient http = HttpSupport.restClient();

    @Override
    public ProviderType type() {
        return ProviderType.GEMINI;
    }

    @Override
    public String chat(ProviderConfig config, String systemPrompt, List<AiMessage> messages) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (AiMessage m : messages) {
            String role = ChatMessageRoles.isAssistant(m.role()) ? "model" : "user";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", m.content()))));
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", contents);

        JsonNode json = post(config, "/models/" + config.getModel() + ":generateContent", body);
        JsonNode parts = json.path("candidates").path(0).path("content").path("parts");
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                text.append(part.get("text").asText());
            }
        }
        if (text.isEmpty()) {
            String reason = json.path("promptFeedback").path("blockReason").asText(
                    json.path("candidates").path(0).path("finishReason").asText("unknown"));
            throw new AiException("Gemini returned no text (reason: " + reason + ")");
        }
        return text.toString();
    }

    @Override
    public boolean supportsEmbeddings() {
        return true;
    }

    @Override
    public List<float[]> embed(ProviderConfig config, List<String> texts) {
        String model = config.getEmbeddingModel();
        List<float[]> result = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += 100) { // Gemini allows up to 100 per batch
            List<Map<String, Object>> requests = new ArrayList<>();
            for (String t : texts.subList(start, Math.min(start + 100, texts.size()))) {
                requests.add(Map.of("model", "models/" + model, "content", Map.of("parts", List.of(Map.of("text", t)))));
            }
            JsonNode json = post(config, "/models/" + model + ":batchEmbedContents", Map.of("requests", requests));
            for (JsonNode e : json.path("embeddings")) {
                result.add(HttpSupport.toFloats(e.path("values")));
            }
        }
        return result;
    }

    private JsonNode post(ProviderConfig config, String path, Object body) {
        String url = HttpSupport.trimSlash(HttpSupport.orDefault(config.getBaseUrl(), DEFAULT_BASE_URL)) + path;
        try {
            return http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-goog-api-key", config.getApiKey() == null ? "" : config.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw HttpSupport.toAiException(config.getName(), url.replaceAll(":\\w+$", ""), e);
        }
    }
}
