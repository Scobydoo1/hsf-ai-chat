package com.hsf.aichat.service;

import com.hsf.aichat.entity.DocumentChunk;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The "R" in RAG: find the document pieces most related to the question.
 * 1. Vector search: embed the question, compare with each chunk's vector (cosine similarity).
 * 2. If no vectors are available (no embedding provider, or documents embedded with another model),
 *    fall back to keyword search (TF-IDF), which needs no AI at all.
 */
@Service
public class RetrievalService {

    public static final int TOP_K = 4;

    public record Hit(DocumentChunk chunk, double score, String method) {
    }

    private final DocumentChunkRepository chunkRepository;
    private final ProviderService providerService;

    public RetrievalService(DocumentChunkRepository chunkRepository, ProviderService providerService) {
        this.chunkRepository = chunkRepository;
        this.providerService = providerService;
    }

    public List<Hit> search(String question) {
        Optional<ProviderConfig> embedder = providerService.embeddingProvider();
        if (embedder.isPresent()) {
            List<DocumentChunk> chunks = chunkRepository.findByEmbeddingModel(ProviderService.embeddingKey(embedder.get()));
            if (!chunks.isEmpty()) {
                return vectorSearch(question, chunks, embedder.get());
            }
        }
        return keywordSearch(question, chunkRepository.findAllWithDocument());
    }

    private List<Hit> vectorSearch(String question, List<DocumentChunk> chunks, ProviderConfig embedder) {
        float[] q = providerService.implementation(embedder).embed(embedder, List.of(question)).get(0);
        return chunks.stream()
                .filter(c -> c.getEmbedding() != null)
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
