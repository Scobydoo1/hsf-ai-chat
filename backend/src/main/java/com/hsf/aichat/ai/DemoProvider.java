package com.hsf.aichat.ai;

import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake AI that runs offline, so the app works on first start with no API key.
 * Chat: echoes your question (and the best document passage in RAG mode).
 * Embeddings: "feature hashing" - each word is hashed into one of 512 slots. Crude, but real vector search.
 */
@Component
public class DemoProvider implements ChatProvider {

    public static final String EMBEDDING_MODEL = "hashing-512";
    private static final int DIMENSIONS = 512;
    private static final Pattern FIRST_SOURCE = Pattern.compile("\\[1] \\(([^)]*)\\)\\n(.{1,400})", Pattern.DOTALL);

    @Override
    public ProviderType type() {
        return ProviderType.DEMO;
    }

    @Override
    public String chat(ProviderConfig config, String systemPrompt, List<AiMessage> messages) {
        String question = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        StringBuilder reply = new StringBuilder("**Demo mode** (offline, no API key)\n\nYou asked: _")
                .append(question.replace("\n", " ")).append("_\n\n");
        Matcher m = FIRST_SOURCE.matcher(systemPrompt);
        if (m.find()) {
            reply.append("The most relevant passage I found is from **").append(m.group(1)).append("** [1]:\n\n> ")
                    .append(m.group(2).trim().replace("\n", " ")).append("…\n\n");
        }
        reply.append("Add a real provider (Gemini, OpenAI, Claude, Groq…) in **Settings** to get real answers.");
        return reply.toString();
    }

    @Override
    public boolean supportsEmbeddings() {
        return true;
    }

    @Override
    public List<float[]> embed(ProviderConfig config, List<String> texts) {
        List<float[]> result = new ArrayList<>();
        for (String text : texts) {
            float[] v = new float[DIMENSIONS];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (word.length() < 2) {
                    continue;
                }
                int h = java.util.Arrays.hashCode(word.getBytes(StandardCharsets.UTF_8));
                v[Math.floorMod(h, DIMENSIONS)] += 1f;
            }
            result.add(v);
        }
        return result;
    }
}
