package com.hsf.aichat.service;

import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.entity.DocumentChunk;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.DocumentChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The "R" in RAG: find the document pieces most related to the question, in all documents or only the chosen ones.
 * 1. Vector search: embed the question, compare with each chunk's vector (cosine similarity).
 *    Only when EVERY chunk in scope has a vector from the current embedding model (vectors of different models
 *    cannot be compared).
 * 2. Otherwise (no embedding provider, documents embedded with another model, or the embedding call failed or
 *    failed a moment ago) keyword search (TF-IDF), which needs no AI at all.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    public static final int TOP_K = 4;

    static final String EMBEDDING_FAILED_NOTE = "The embedding service is busy, so the document search used keywords.";

    public record Hit(DocumentChunk chunk, double score, String method) {
    }

    /**
     * hits: the best passages. documentsSearched: how many documents were in scope.
     * note: something the user should know about this search (shown with the answer), or null.
     */
    public record SearchResult(List<Hit> hits, int documentsSearched, String note) {
    }

    private final DocumentChunkRepository chunkRepository;
    private final ProviderService providerService;
    private final ProviderHealth health;

    public RetrievalService(DocumentChunkRepository chunkRepository, ProviderService providerService,
                            ProviderHealth health) {
        this.chunkRepository = chunkRepository;
        this.providerService = providerService;
        this.health = health;
    }

    /** Search all documents */
    public List<Hit> search(String question) {
        return search(question, null).hits();
    }

    /** Search only the given documents; documentIds = null means all documents. */
    public SearchResult search(String question, Collection<Long> documentIds) {
        List<DocumentChunk> chunks;
        if (documentIds == null) {
            chunks = chunkRepository.findAllWithDocument();
        } else {
            chunks = documentIds.isEmpty() ? List.of() : chunkRepository.findByDocumentIdIn(documentIds);
        }
        if (chunks.isEmpty()) {
            return new SearchResult(List.of(), 0, null);
        }
        int documents = (int) chunks.stream().map(c -> c.getDocument().getId()).distinct().count();

        Optional<ProviderConfig> found = providerService.embeddingProvider();
        if (found.isPresent() && allEmbeddedWith(chunks, ProviderService.embeddingKey(found.get()))) {
            ProviderConfig embedder = found.get();
            if (health.isEmbeddingResting(embedder.getId())) {
                // it failed a moment ago (e.g. its free quota is used up): do not wait for another failing call
                return new SearchResult(keywordSearch(question, chunks), documents, EMBEDDING_FAILED_NOTE);
            }
            try {
                List<Hit> hits = vectorSearch(question, chunks, embedder);
                health.embeddingSucceeded(embedder.getId());
                return new SearchResult(hits, documents, null);
            } catch (AiException e) {
                // keyword search still finds passages. Only the embeddings rest: the chat of this provider is fine.
                health.embeddingFailed(embedder, e);
                log.warn("Embedding the question failed, using keyword search: {}", e.getMessage());
                return new SearchResult(keywordSearch(question, chunks), documents, EMBEDDING_FAILED_NOTE);
            }
        }
        return new SearchResult(keywordSearch(question, chunks), documents, null);
    }

    private static boolean allEmbeddedWith(List<DocumentChunk> chunks, String embeddingKey) {
        return chunks.stream().allMatch(c -> embeddingKey.equals(c.getEmbeddingModel()) && c.getEmbedding() != null);
    }

    private List<Hit> vectorSearch(String question, List<DocumentChunk> chunks, ProviderConfig embedder) {
        List<float[]> vectors = providerService.implementation(embedder).embed(embedder, List.of(question));
        if (vectors.isEmpty() || vectors.get(0) == null) {
            throw new AiException(embedder.getName() + " returned no embedding for the question");
        }
        float[] q = vectors.get(0);
        return chunks.stream()
                .map(c -> new Hit(c, VectorMath.cosine(q, VectorMath.fromBytes(c.getEmbedding())), "vector"))
                .sorted(Comparator.comparingDouble(Hit::score).reversed())
                .limit(TOP_K)
                .toList();
    }

    private List<Hit> keywordSearch(String question, List<DocumentChunk> chunks) {
        Set<String> terms = new HashSet<>(tokenize(question));
        if (terms.isEmpty() || chunks.isEmpty()) {
            return List.of();
        }
        // document frequency: in how many chunks does each term appear?
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> tokenized = new ArrayList<>();
        for (DocumentChunk c : chunks) {
            List<String> tokens = tokenize(c.getContent());
            tokenized.add(tokens);
            for (String t : new HashSet<>(tokens)) {
                if (terms.contains(t)) {
                    df.merge(t, 1, Integer::sum);
                }
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            List<String> tokens = tokenized.get(i);
            double score = 0;
            for (String t : terms) {
                long tf = tokens.stream().filter(t::equals).count();
                if (tf > 0) {
                    score += tf * Math.log(1 + (double) chunks.size() / df.get(t));
                }
            }
            if (score > 0) {
                hits.add(new Hit(chunks.get(i), score / Math.sqrt(tokens.size() + 1), "keyword"));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.stream().limit(TOP_K).toList();
    }

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2) {
                out.add(w);
            }
        }
        return out;
    }
}
