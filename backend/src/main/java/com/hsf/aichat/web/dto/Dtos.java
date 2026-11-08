package com.hsf.aichat.web.dto;

import com.hsf.aichat.entity.ChatMode;
import com.hsf.aichat.entity.ProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Request and response bodies of the REST API (Java records = small immutable classes). */
public final class Dtos {

    private Dtos() {
    }

    /**
     * The database keeps times as LocalDateTime in the server's time zone (UTC on Render).
     * Sending them WITH their offset, e.g. "2026-11-08T03:30:00.123Z", lets every browser show and group them
     * ("Today" / "Earlier") in the user's own time zone. Milliseconds are enough and every browser parses them.
     */
    public static OffsetDateTime withZone(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MILLIS).atZone(ZoneId.systemDefault()).toOffsetDateTime();
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

    /** cooldownSeconds: 0 = healthy, otherwise the provider is resting after an error (see lastError) */
    public record ProviderResponse(
            Long id, String name, ProviderType type, String baseUrl, String model, String embeddingModel,
            String maskedKey, boolean hasKey, boolean active, boolean useForEmbeddings, boolean supportsEmbeddings,
            int cooldownSeconds, String lastError) {
    }

    public record TestResult(boolean ok, String reply, String error) {
    }

    // ---------- Status ----------

    /** reindexing: true while outdated documents are re-indexed in the background */
    public record StatusResponse(String status, boolean passwordRequired, String activeProvider, String activeModel,
                                 String embeddingProvider, boolean reindexing) {
    }

    // ---------- Chat ----------

    public record CreateConversationRequest(ChatMode mode) {
    }

    public record ConversationResponse(Long id, String title, ChatMode mode, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * useDocuments: true = search documents, false = plain chat, null = old clients (search all documents only
     * in a RAG conversation). documentIds: which documents to search, null or empty = all of them.
     * providerId: answer with this provider instead of the active one. autoFallback: null or true = if the AI
     * fails, try the other providers.
     */
    public record SendMessageRequest(
            @NotBlank @Size(max = 20000) String content,
            Boolean useDocuments,
            @Size(max = 500) List<Long> documentIds,
            Long providerId,
            Boolean autoFallback) {

        /** The same request without the spaces around the text */
        public SendMessageRequest trimmed() {
            return new SendMessageRequest(content.trim(), useDocuments, documentIds, providerId, autoFallback);
        }
    }

    /** method: "vector" or "keyword". documentId is null for answers saved before it existed. */
    public record SourceResponse(Long documentId, String documentName, int chunkIndex, double score, String method,
                                 String snippet) {
    }

    /**
     * notice: why another AI answered, or a note about the document search (null = nothing special).
     * durationMs: search + AI time of an answer. documentsSearched: null = documents were not used.
     */
    public record MessageResponse(Long id, String role, String content, String provider, String model,
                                  List<SourceResponse> sources, OffsetDateTime createdAt,
                                  String notice, Long durationMs, Integer documentsSearched) {
    }

    // ---------- Documents ----------

    public record TextDocumentRequest(@NotBlank @Size(max = 200) String name, @NotBlank String content) {
    }

    /** searchMode: "vector" (current embedding model), "outdated" (another model, re-indexed soon) or "keyword" */
    public record DocumentResponse(Long id, String name, int charCount, int chunkCount, String embeddingModel,
                                   OffsetDateTime createdAt, String searchMode) {
    }

    public record UploadResponse(DocumentResponse document, String warning) {
    }

    public record ReindexResponse(int documents, int chunks, String embeddingModel) {
    }
}
