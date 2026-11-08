package com.hsf.aichat.service;

import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.ai.AiMessage;
import com.hsf.aichat.ai.ChatProvider;
import com.hsf.aichat.ai.ProviderRegistry;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import com.hsf.aichat.repository.ProviderConfigRepository;
import com.hsf.aichat.web.dto.Dtos.ProviderRequest;
import com.hsf.aichat.web.dto.Dtos.ProviderResponse;
import com.hsf.aichat.web.dto.Dtos.TestResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Manages the AI platform settings: add, edit, delete, switch the active one, test a key. */
@Service
public class ProviderService {

    /** At most this many providers are tried for one answer (each try can take a while) */
    static final int MAX_CHAIN = 3;

    private final ProviderConfigRepository repository;
    private final ProviderRegistry registry;
    private final ProviderHealth health;
    private final ApplicationEventPublisher events;

    public ProviderService(ProviderConfigRepository repository, ProviderRegistry registry, ProviderHealth health,
                           ApplicationEventPublisher events) {
        this.repository = repository;
        this.registry = registry;
        this.health = health;
        this.events = events;
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

    /**
     * The providers to try for one answer, in order: the preferred one first, then the others as backups.
     * Backups = the other providers that are not the Demo, have an API key or a URL (e.g. Ollama) and are not resting.
     * A resting preferred provider is skipped when there is a backup (no point waiting for a call that will fail),
     * unless chosenByUser: the user asked for it by name ("Ask Groq"), so it is always tried first.
     * The offline Demo never fails, so it never switches, and it is never used as a backup.
     */
    public List<ProviderConfig> fallbackChain(ProviderConfig preferred, boolean chosenByUser) {
        if (preferred.getType() == ProviderType.DEMO) {
            return List.of(preferred);
        }
        List<ProviderConfig> backups = repository.findAllByOrderByIdAsc().stream()
                .filter(c -> !c.getId().equals(preferred.getId()))
                .filter(c -> c.getType() != ProviderType.DEMO)
                .filter(c -> hasText(c.getApiKey()) || hasText(c.getBaseUrl()))
                .filter(c -> !health.isResting(c.getId()))
                .toList();
        List<ProviderConfig> chain = new ArrayList<>();
        if (chosenByUser || !health.isResting(preferred.getId()) || backups.isEmpty()) {
            chain.add(preferred);
        }
        chain.addAll(backups);
        return List.copyOf(chain.subList(0, Math.min(MAX_CHAIN, chain.size())));
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
        events.publishEvent(new ProvidersChangedEvent());
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
        health.clear(id); // new settings deserve a new chance
        events.publishEvent(new ProvidersChangedEvent());
        return toResponse(repository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        ProviderConfig c = get(id);
        repository.delete(c);
        repository.flush();
        health.clear(id);
        if (c.isActive()) {
            repository.findAllByOrderByIdAsc().stream().findFirst().ifPresent(next -> next.setActive(true));
        }
        events.publishEvent(new ProvidersChangedEvent());
    }

    /** "Switch platform": make this provider the one used for chat */
    @Transactional
    public ProviderResponse activate(Long id) {
        ProviderConfig target = get(id);
        for (ProviderConfig c : repository.findAll()) {
            c.setActive(c.getId().equals(target.getId()));
        }
        health.clear(id); // the user chose it on purpose: stop skipping it
        events.publishEvent(new ProvidersChangedEvent());
        return toResponse(target);
    }

    /** Send a tiny prompt to check that the key, URL and model work */
    public TestResult test(Long id) {
        ProviderConfig c = get(id);
        try {
            String reply = registry.get(c.getType()).chat(c, "You are a connection test. Answer in one short sentence.",
                    List.of(new AiMessage("user", "Say hello and tell me which model you are.")));
            health.succeeded(c.getId());
            return new TestResult(true, reply, null);
        } catch (AiException e) {
            health.failed(c, e);
            return new TestResult(false, null, e.getMessage());
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
        // documents embedded with another model are re-indexed in the background, after this transaction commits
        events.publishEvent(new EmbeddingProviderChangedEvent(target.getId()));
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
                c.getEmbeddingModel(), mask(key), hasText(key), c.isActive(),
                c.isUseForEmbeddings(), registry.get(c.getType()).supportsEmbeddings(),
                health.cooldownSeconds(c.getId()), health.lastError(c.getId()));
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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
