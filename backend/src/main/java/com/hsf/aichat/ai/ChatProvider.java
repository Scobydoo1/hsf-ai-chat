package com.hsf.aichat.ai;

import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;

import java.util.List;

/**
 * The one interface every AI platform implements.
 * The rest of the app only talks to this interface, so switching platform = switching the active ProviderConfig.
 */
public interface ChatProvider {

    ProviderType type();

    /** Send the system prompt + conversation, return the AI's answer as text (Markdown). */
    String chat(ProviderConfig config, String systemPrompt, List<AiMessage> messages);

    /** Can this platform turn text into embedding vectors (needed for vector search in RAG)? */
    default boolean supportsEmbeddings() {
        return false;
    }

    /** Turn each text into a vector. Same order as the input. */
    default List<float[]> embed(ProviderConfig config, List<String> texts) {
        throw new AiException(type() + " does not offer an embeddings API");
    }
}
