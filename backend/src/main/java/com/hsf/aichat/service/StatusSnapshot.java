package com.hsf.aichat.service;

import com.hsf.aichat.entity.ProviderConfig;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * The provider names that GET /api/status shows, kept in memory.
 * Render's health check and the front-end's keep-awake ping call /api/status again and again. If it read the
 * database, every ping would cost a round trip and the free Neon database could never go to sleep (which uses up
 * its monthly compute hours). So the names are read at start-up and again after every committed provider change.
 */
@Component
public class StatusSnapshot {

    /** null = no such provider */
    public record Providers(String activeName, String activeModel, String embeddingName) {
    }

    private final ProviderService providerService;
    /** null only until the first read */
    private volatile Providers providers;

    public StatusSnapshot(ProviderService providerService) {
        this.providerService = providerService;
    }

    /** The latest names, without touching the database (except once, if a request comes before start-up ended) */
    public Providers providers() {
        Providers current = providers;
        return current != null ? current : refresh();
    }

    /** After ProviderSeeder has added the providers from the environment variables */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refresh();
    }

    /** Once the change is committed, so the saved state is read */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProvidersChanged(ProvidersChangedEvent event) {
        refresh();
    }

    /** synchronized: two changes at the same moment cannot leave the older names behind */
    synchronized Providers refresh() {
        Optional<ProviderConfig> active = providerService.active();
        providers = new Providers(active.map(ProviderConfig::getName).orElse(null),
                active.map(ProviderConfig::getModel).orElse(null),
                providerService.embeddingProvider().map(ProviderConfig::getName).orElse(null));
        return providers;
    }
}
