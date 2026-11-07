package com.hsf.aichat.web.dto;

import com.hsf.aichat.entity.ChatMode;
import com.hsf.aichat.entity.ProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** Request and response bodies of the REST API (Java records = small immutable classes). */
public final class Dtos {

    private Dtos() {
    }

    // ---------- Providers ----------

    /** apiKey: leave empty on update to keep the saved key */
    public record ProviderRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull ProviderType type,
            @Size(max = 300) String baseUrl,
            @NotBlank @Size(max = 100) String model,
            @Size(max = 100) String embeddingModel,
            @Size(max = 500) String apiKey,
            Boolean useForEmbeddings) {
    }

    public record ProviderResponse(
            Long id, String name, ProviderType type, String baseUrl, String model, String embeddingModel,
            String maskedKey, boolean hasKey, boolean active, boolean useForEmbeddings, boolean supportsEmbeddings) {
    }

    public record TestResult(boolean ok, String reply, String error) {
    }

    // ---------- Status ----------

    public record StatusResponse(String status, boolean passwordRequired, String activeProvider, String activeModel,
                                 String embeddingProvider) {
    }

    // ---------- Chat ----------

    public record CreateConversationRequest(ChatMode mode) {
    }

    public record ConversationResponse(Long id, String title, ChatMode mode, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 20000) String content) {
    }

    public record SourceResponse(String documentName, int chunkIndex, double score, String method, String snippet) {
    }

    public record MessageResponse(Long id, String role, String content, String provider, String model,
                                  List<SourceResponse> sources, LocalDateTime createdAt) {
    }

    // ---------- Documents ----------

    public record TextDocumentRequest(@NotBlank @Size(max = 200) String name, @NotBlank String content) {
    }

    public record DocumentResponse(Long id, String name, int charCount, int chunkCount, String embeddingModel,
                                   LocalDateTime createdAt) {
    }

    public record UploadResponse(DocumentResponse document, String warning) {
    }

    public record ReindexResponse(int documents, int chunks, String embeddingModel) {
    }
}
