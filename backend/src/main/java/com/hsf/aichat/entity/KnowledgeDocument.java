package com.hsf.aichat.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** A file or pasted text the AI can answer from in RAG mode. Its text is stored as chunks. */
@Entity
@Table(name = "documents")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private int charCount;

    @Column(nullable = false)
    private int chunkCount;

    /** e.g. "GEMINI:gemini-embedding-001"; null = no vectors, keyword search only */
    @Column(length = 150)
    private String embeddingModel;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public KnowledgeDocument() {
    }

    public KnowledgeDocument(String name, int charCount) {
        this.name = name;
        this.charCount = charCount;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getCharCount() { return charCount; }

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
