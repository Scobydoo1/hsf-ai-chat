package com.hsf.aichat.service;

import com.hsf.aichat.ai.AiMessage;
import com.hsf.aichat.ai.ChatProvider;
import com.hsf.aichat.ai.ProviderRegistry;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.ProviderConfigRepository;
import com.hsf.aichat.web.dto.Dtos.ProviderRequest;
import com.hsf.aichat.web.dto.Dtos.ProviderResponse;
import com.hsf.aichat.web.dto.Dtos.TestResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Manages the AI platform settings: add, edit, delete, switch the active one, test a key. */
@Service
public class ProviderService {

    private final ProviderConfigRepository repository;
    private final ProviderRegistry registry;

    public ProviderService(ProviderConfigRepository repository, ProviderRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    public List<ProviderResponse> list() {
        return repository.findAllByOrderByIdAsc().stream().map(this::toResponse).toList();
    }

    public ProviderConfig get(Long id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("Provider " + id + " not found"));
    }

    /** The provider used for chat answers */
    public Optional<ProviderConfig> active() {
        return repository.findFirstByActiveTrue();
    }

    /** The provider used to embed documents and questions for RAG (only if it can embed and has a model) */
    public Optional<ProviderConfig> embeddingProvider() {
        return repository.findFirstByUseForEmbeddingsTrue()
                .filter(c -> registry.get(c.getType()).supportsEmbeddings())
                .filter(c -> c.getEmbeddingModel() != null && !c.getEmbeddingModel().isBlank());
    }

    /** Identifies an embedding space, e.g. "GEMINI:gemini-embedding-001". Vectors only compare within one key. */
    public static String embeddingKey(ProviderConfig c) {
        return c.getType() + ":" + c.getEmbeddingModel();
    }

    @Transactional
    public ProviderResponse create(ProviderRequest req) {
        ProviderConfig c = new ProviderConfig();
        apply(c, req);
        c.setApiKey(blankToNull(req.apiKey()));
        if (repository.findFirstByActiveTrue().isEmpty()) {
            c.setActive(true); // the first provider becomes active automatically
        }
        ProviderConfig saved = repository.save(c);
        if (Boolean.TRUE.equals(req.useForEmbeddings())) {
            markForEmbeddings(saved);
        }
        return toResponse(saved);
    }

    @Transactional
    public ProviderResponse update(Long id, ProviderRequest req) {
        ProviderConfig c = get(id);
        apply(c, req);
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            c.setApiKey(req.apiKey().trim()); // empty = keep the saved key
        }
        if (req.useForEmbeddings() != null) {
            if (req.useForEmbeddings()) {
                markForEmbeddings(c);
            } else {
                c.setUseForEmbeddings(false);
            }
        }
        return toResponse(repository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        ProviderConfig c = get(id);
        repository.delete(c);
        repository.flush();
        if (c.isActive()) {
            repository.findAllByOrderByIdAsc().stream().findFirst().ifPresent(next -> next.setActive(true));
        }
    }

    /** "Switch platform": make this provider the one used for chat */
    @Transactional
    public ProviderResponse activate(Long id) {
        ProviderConfig target = get(id);
        for (ProviderConfig c : repository.findAll()) {
            c.setActive(c.getId().equals(target.getId()));
        }
        return toResponse(target);
    }

    /** Send a tiny prompt to check that the key, URL and model work */
    public TestResult test(Long id) {
        ProviderConfig c = get(id);
        try {
            String reply = registry.get(c.getType()).chat(c, "You are a connection test. Answer in one short sentence.",
                    List.of(new AiMessage("user", "Say hello and tell me which model you are.")));
            return new TestResult(true, reply, null);
        } catch (Exception e) {
            return new TestResult(false, null, e.getMessage());
        }
    }

    public ChatProvider implementation(ProviderConfig c) {
        return registry.get(c.getType());
    }

    private void markForEmbeddings(ProviderConfig target) {
        for (ProviderConfig c : repository.findAll()) {
            c.setUseForEmbeddings(c.getId().equals(target.getId()));
        }
        target.setUseForEmbeddings(true);
    }

    private void apply(ProviderConfig c, ProviderRequest req) {
        c.setName(req.name().trim());
        c.setType(req.type());
        c.setBaseUrl(blankToNull(req.baseUrl()));
        c.setModel(req.model().trim());
        c.setEmbeddingModel(blankToNull(req.embeddingModel()));
    }

    private ProviderResponse toResponse(ProviderConfig c) {
        String key = c.getApiKey();
        return new ProviderResponse(c.getId(), c.getName(), c.getType(), c.getBaseUrl(), c.getModel(),
                c.getEmbeddingModel(), mask(key), key != null && !key.isBlank(), c.isActive(),
                c.isUseForEmbeddings(), registry.get(c.getType()).supportsEmbeddings());
    }

    /** Never send a full key back to the browser: "sk-pr••••abcd" */
    static String mask(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() <= 10) {
            return "••••";
        }
        return key.substring(0, 5) + "••••" + key.substring(key.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
