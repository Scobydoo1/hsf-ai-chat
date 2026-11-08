package com.hsf.aichat.repository;

import com.hsf.aichat.entity.DocumentChunk;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /** Every chunk with its document, in one query */
    @EntityGraph(attributePaths = "document")
    @Query("select c from DocumentChunk c")
    List<DocumentChunk> findAllWithDocument();

    /** The chunks of the chosen documents only, with their document, in one query */
    @EntityGraph(attributePaths = "document")
    List<DocumentChunk> findByDocumentIdIn(Collection<Long> documentIds);

    @EntityGraph(attributePaths = "document")
    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Transactional
    @Modifying
    @Query("delete from DocumentChunk c where c.document.id = :documentId")
    void deleteByDocumentId(Long documentId);
}
