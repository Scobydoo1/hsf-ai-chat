package com.hsf.aichat.service;

import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.entity.DocumentChunk;
import com.hsf.aichat.entity.KnowledgeDocument;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.DocumentChunkRepository;
import com.hsf.aichat.repository.KnowledgeDocumentRepository;
import com.hsf.aichat.web.dto.Dtos.DocumentResponse;
import com.hsf.aichat.web.dto.Dtos.ReindexResponse;
import com.hsf.aichat.web.dto.Dtos.UploadResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.hsf.aichat.web.dto.Dtos.withZone;

/**
 * The knowledge base for RAG: read a file, split it into chunks, embed each chunk, save to the database.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ProviderService providerService;
    private final TransactionTemplate transactions;

    public DocumentService(KnowledgeDocumentRepository documentRepository, DocumentChunkRepository chunkRepository,
                           ProviderService providerService, TransactionTemplate transactions) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.providerService = providerService;
        this.transactions = transactions;
    }

    public List<DocumentResponse> list() {
        // look up the current embedding model once, then compare every document with it
        String currentKey = providerService.embeddingProvider().map(ProviderService::embeddingKey).orElse(null);
        return documentRepository.findAllByOrderByIdDesc().stream().map(d -> toResponse(d, currentKey)).toList();
    }

    public UploadResponse upload(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "upload.txt" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        String text;
        if (lower.endsWith(".pdf")) {
            try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
                text = new PDFTextStripper().getText(pdf);
            }
        } else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".html") || lower.endsWith(".xml")) {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Unsupported file type. Use .pdf, .txt, .md, .csv, .json, .html or .xml");
        }
        return addText(name, text);
    }

    public UploadResponse addText(String name, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The document has no text (a scanned PDF needs OCR first)");
        }
        List<String> pieces = TextChunker.split(text);
        KnowledgeDocument doc = documentRepository.save(new KnowledgeDocument(name, text.length()));

        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            chunks.add(new DocumentChunk(doc, i, pieces.get(i)));
        }
        String warning = embed(doc, chunks);
        chunkRepository.saveAll(chunks);
        doc.setChunkCount(chunks.size());
        documentRepository.save(doc);
        // just embedded with the current model (or not at all, see the warning)
        return new UploadResponse(toResponse(doc, doc.getEmbeddingModel()), warning);
    }

    public void delete(Long id) {
        if (!documentRepository.existsById(id)) {
            throw new NoSuchElementException("Document " + id + " not found");
        }
        chunkRepository.deleteByDocumentId(id);
        documentRepository.deleteById(id);
    }

    /** Re-embed every chunk with the current embedding provider (after you switch embedding model) */
    public ReindexResponse reindex() {
        Optional<ProviderConfig> embedder = providerService.embeddingProvider();
        if (embedder.isEmpty()) {
            throw new IllegalArgumentException("No embedding provider set. In Settings, tick \"Use for embeddings\" on a provider with an embedding model.");
        }
        int total = 0;
        List<KnowledgeDocument> docs = documentRepository.findAll();
        for (KnowledgeDocument doc : docs) {
            List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getId());
            String warning = embed(doc, chunks);
            if (warning != null) {
                throw new IllegalStateException(warning);
            }
            chunkRepository.saveAll(chunks);
            documentRepository.save(doc);
            total += chunks.size();
        }
        return new ReindexResponse(docs.size(), total, ProviderService.embeddingKey(embedder.get()));
    }

    /**
     * Re-embeds one document with the current embedding provider (used by the background re-index).
     * The AI call comes first and no transaction is open during it. If it fails, the exception goes up
     * and nothing is changed, so the document keeps its old vectors.
     */
    public void reembed(KnowledgeDocument doc) {
        ProviderConfig cfg = providerService.embeddingProvider()
                .orElseThrow(() -> new IllegalStateException("No embedding provider is set"));
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getId());
        if (chunks.isEmpty()) {
            return;
        }
        List<float[]> vectors = providerService.implementation(cfg)
                .embed(cfg, chunks.stream().map(DocumentChunk::getContent).toList()); // one batch call
        if (vectors.size() != chunks.size()) {
            throw new AiException(cfg.getName() + " returned " + vectors.size() + " vectors for " + chunks.size() + " chunks");
        }
        String key = ProviderService.embeddingKey(cfg);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(VectorMath.toBytes(vectors.get(i)));
            chunks.get(i).setEmbeddingModel(key);
        }
        // one short transaction: the new vectors and the document's model change together
        transactions.executeWithoutResult(status -> {
            chunkRepository.saveAll(chunks);
            documentRepository.updateEmbeddingModel(doc.getId(), key);
        });
    }

    /** Fills in the vectors. Returns a warning (and keeps keyword search) if embedding is not possible. */
    private String embed(KnowledgeDocument doc, List<DocumentChunk> chunks) {
        Optional<ProviderConfig> embedder = providerService.embeddingProvider();
        if (embedder.isEmpty()) {
            doc.setEmbeddingModel(null);
            return "No embedding provider is set, so this document uses keyword search only.";
        }
        ProviderConfig cfg = embedder.get();
        try {
            List<float[]> vectors = providerService.implementation(cfg)
                    .embed(cfg, chunks.stream().map(DocumentChunk::getContent).toList());
            String key = ProviderService.embeddingKey(cfg);
            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).setEmbedding(VectorMath.toBytes(vectors.get(i)));
                chunks.get(i).setEmbeddingModel(key);
            }
            doc.setEmbeddingModel(key);
            return null;
        } catch (Exception e) {
            log.warn("Embedding failed for {}: {}", doc.getName(), e.getMessage());
            doc.setEmbeddingModel(null);
            return "Embedding failed (" + e.getMessage() + "). The document was saved and uses keyword search.";
        }
    }

    private static DocumentResponse toResponse(KnowledgeDocument d, String currentEmbeddingKey) {
        return new DocumentResponse(d.getId(), d.getName(), d.getCharCount(), d.getChunkCount(), d.getEmbeddingModel(),
                withZone(d.getCreatedAt()), searchMode(d, currentEmbeddingKey));
    }

    /**
     * How questions about this document are searched: "vector" = embedded with the current embedding model,
     * "outdated" = embedded with another model (re-indexed in the background), "keyword" = no usable vectors
     * (no embedding provider, or embedding failed).
     */
    static String searchMode(KnowledgeDocument d, String currentEmbeddingKey) {
        if (d.getEmbeddingModel() == null || currentEmbeddingKey == null) {
            return "keyword";
        }
        return d.getEmbeddingModel().equals(currentEmbeddingKey) ? "vector" : "outdated";
    }
}
