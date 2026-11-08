package com.hsf.aichat.repository;

import com.hsf.aichat.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findAllByOrderByIdDesc();

    /** Changes only the embedding model column (used by the background re-index) */
    @Transactional
    @Modifying
    @Query("update KnowledgeDocument d set d.embeddingModel = :embeddingModel where d.id = :documentId")
    void updateEmbeddingModel(Long documentId, String embeddingModel);
}
