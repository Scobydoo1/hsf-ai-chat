package com.hsf.aichat.repository;

import com.hsf.aichat.entity.DocumentChunk;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /** All chunks embedded with one model, e.g. "GEMINI:gemini-embedding-001" */
    @EntityGraph(attributePaths = "document")
    List<DocumentChunk> findByEmbeddingModel(String embeddingModel);

    @EntityGraph(attributePaths = "document")
    @Query("select c from DocumentChunk c")
    List<DocumentChunk> findAllWithDocument();

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Transactional
    @Modifying
    @Query("delete from DocumentChunk c where c.document.id = :documentId")
    void deleteByDocumentId(Long documentId);
}
