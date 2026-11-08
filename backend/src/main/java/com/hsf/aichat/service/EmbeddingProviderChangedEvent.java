package com.hsf.aichat.service;

/** Published when a provider is chosen for embeddings, so EmbeddingMaintenance can re-index outdated documents. */
public record EmbeddingProviderChangedEvent(Long providerId) {
}
