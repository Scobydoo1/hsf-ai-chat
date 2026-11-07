package com.hsf.aichat.config;

import com.hsf.aichat.ai.DemoProvider;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import com.hsf.aichat.repository.ProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * On start-up:
 * - first run: create the offline Demo provider, so the app works with no key at all;
 * - for each API key found in the environment (GEMINI_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY, GROQ_API_KEY),
 *   create that provider if it does not exist yet. Handy on Render: set the env var, restart, done.
 * Everything can still be changed later on the Settings page.
 */
@Component
@Order(1)
public class ProviderSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProviderSeeder.class);

    private final ProviderConfigRepository repository;
    private final Environment env;

    public ProviderSeeder(ProviderConfigRepository repository, Environment env) {
        this.repository = repository;
        this.env = env;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (repository.count() == 0) {
            ProviderConfig demo = new ProviderConfig("Demo (offline, no key)", ProviderType.DEMO, null, "demo",
                    DemoProvider.EMBEDDING_MODEL, null);
            demo.setActive(true);
            demo.setUseForEmbeddings(true);
            repository.save(demo);
        }
        seed("GEMINI_API_KEY", "Google Gemini", ProviderType.GEMINI, null, "gemini-3.8-flash", "gemini-embedding-001");
        seed("OPENAI_API_KEY", "OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5-mini", "text-embedding-3-small");
        seed("ANTHROPIC_API_KEY", "Anthropic Claude", ProviderType.ANTHROPIC, null, "claude-opus-5-5", null);
        seed("GROQ_API_KEY", "Groq", ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", null);
    }

    private void seed(String envVar, String name, ProviderType type, String baseUrl, String model, String embeddingModel) {
        String key = env.getProperty(envVar);
        if (key == null || key.isBlank() || repository.existsByName(name)) {
            return;
        }
        ProviderConfig config = repository.save(new ProviderConfig(name, type, baseUrl, model, embeddingModel, key.trim()));
        log.info("Added provider '{}' from {}", name, envVar);

        // If the Demo provider is still in charge, hand chat (and embeddings, if possible) to the real one
        repository.findFirstByActiveTrue().filter(p -> p.getType() == ProviderType.DEMO).ifPresent(demo -> {
            demo.setActive(false);
            config.setActive(true);
        });
        if (embeddingModel != null) {
            repository.findFirstByUseForEmbeddingsTrue().filter(p -> p.getType() == ProviderType.DEMO).ifPresent(demo -> {
                demo.setUseForEmbeddings(false);
                config.setUseForEmbeddings(true);
            });
        }
    }
}
