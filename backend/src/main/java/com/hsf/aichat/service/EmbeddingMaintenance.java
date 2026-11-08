package com.hsf.aichat.service;

import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.entity.KnowledgeDocument;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.KnowledgeDocumentRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Re-indexes "outdated" documents in the background: documents whose vectors were made by another embedding model
 * (for example by the Demo provider before a Gemini key was added). Vectors of different models cannot be compared,
 * so until then those documents are searched with keywords.
 * Runs after start-up and after the embedding provider changes, on ONE background thread, so it never slows down
 * start-up or a request. It stops at the first error (most likely a rate limit) and keeps the old vectors.
 * Turn it off with APP_AUTO_REINDEX=false.
 */
@Component
public class EmbeddingMaintenance {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMaintenance.class);

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentService documentService;
    private final ProviderService providerService;
    private final ProviderHealth health;
    private final boolean enabled;

    /** A single background thread, so two runs never overlap */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "embedding-reindex");
        thread.setDaemon(true);
        return thread;
    });
    /** A run is waiting for the thread */
    private final AtomicBoolean queued = new AtomicBoolean();
    /** A run is working */
    private final AtomicBoolean running = new AtomicBoolean();

    public EmbeddingMaintenance(KnowledgeDocumentRepository documentRepository, DocumentService documentService,
                                ProviderService providerService, ProviderHealth health,
                                @Value("${app.auto-reindex:true}") boolean enabled) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.providerService = providerService;
        this.health = health;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reindexOutdatedAsync();
    }

    /** Called once the transaction that changed the embedding provider is committed */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEmbeddingProviderChanged(EmbeddingProviderChangedEvent event) {
        reindexOutdatedAsync();
    }

    /** Starts a run in the background and returns at once. A request during a run queues one more run after it. */
    public void reindexOutdatedAsync() {
        if (!enabled || !queued.compareAndSet(false, true)) {
            return; // switched off, or a run is already waiting
        }
        try {
            worker.execute(() -> {
                running.set(true);
                queued.set(false);
                try {
                    reindexOutdated();
                } catch (Exception e) {
                    log.warn("Background re-index failed: {}", e.getMessage());
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            queued.set(false); // the app is shutting down
        }
    }

    /** Shown as "reindexing" in GET /api/status */
    public boolean isRunning() {
        return running.get() || queued.get();
    }

    private void reindexOutdated() {
        Optional<ProviderConfig> embedder = providerService.embeddingProvider();
        if (embedder.isEmpty()) {
            return; // no embedding provider: documents use keyword search
        }
        String key = ProviderService.embeddingKey(embedder.get());
        List<KnowledgeDocument> outdated = documentRepository.findAll().stream()
                .filter(d -> !key.equals(d.getEmbeddingModel()))
                .toList();
        if (outdated.isEmpty()) {
            return;
        }
        log.info("Re-indexing {} document(s) with {} in the background", outdated.size(), key);
        for (KnowledgeDocument doc : outdated) {
            try {
                documentService.reembed(doc);
            } catch (Exception e) {
                // keep the old vectors; the next start-up, provider change or "Re-index all" tries again
                if (e instanceof AiException aiError) {
                    health.embeddingFailed(embedder.get(), aiError); // questions skip vector search while it rests
                }
                log.warn("Background re-index stopped at '{}': {}", doc.getName(), e.getMessage());
                return;
            }
        }
        log.info("Background re-index finished");
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
