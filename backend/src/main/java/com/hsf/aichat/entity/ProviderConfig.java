package com.hsf.aichat.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One AI platform the user has configured: which API, which model, and the API key.
 * Exactly one config is "active" (used for chat); one may be marked "useForEmbeddings" (used for RAG).
 */
@Entity
@Table(name = "ai_providers")
public class ProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderType type;

    /** Empty = the platform's default URL */
    @Column(length = 300)
    private String baseUrl;

    @Column(nullable = false, length = 100)
    private String model;

    /** Model used to turn text into vectors for RAG. Empty = this provider is not used for embeddings. */
    @Column(length = 100)
    private String embeddingModel;

    @Convert(converter = ApiKeyConverter.class)
    @Column(length = 1000)
    private String apiKey;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private boolean useForEmbeddings;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public ProviderConfig() {
    }

    public ProviderConfig(String name, ProviderType type, String baseUrl, String model, String embeddingModel, String apiKey) {
        this.name = name;
        this.type = type;
        this.baseUrl = baseUrl;
        this.model = model;
        this.embeddingModel = embeddingModel;
        this.apiKey = apiKey;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ProviderType getType() { return type; }
    public void setType(ProviderType type) { this.type = type; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isUseForEmbeddings() { return useForEmbeddings; }
    public void setUseForEmbeddings(boolean useForEmbeddings) { this.useForEmbeddings = useForEmbeddings; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
