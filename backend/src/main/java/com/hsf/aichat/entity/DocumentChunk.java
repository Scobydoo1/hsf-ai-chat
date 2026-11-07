package com.hsf.aichat.entity;

import jakarta.persistence.*;

/**
 * A ~1000-character piece of a document, plus its embedding vector.
 * The vector is stored as bytes (float32), so it works the same on SQL Server and PostgreSQL.
 */
@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "document_id")
    private KnowledgeDocument document;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, length = 10000)
    private String content;

    @Column(length = 65536)
    private byte[] embedding;

    @Column(length = 150)
    private String embeddingModel;

    public DocumentChunk() {
    }

    public DocumentChunk(KnowledgeDocument document, int chunkIndex, String content) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public Long getId() { return id; }
    public KnowledgeDocument getDocument() { return document; }
    public int getChunkIndex() { return chunkIndex; }
    public String getContent() { return content; }

    public byte[] getEmbedding() { return embedding; }
    public void setEmbedding(byte[] embedding) { this.embedding = embedding; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
}
