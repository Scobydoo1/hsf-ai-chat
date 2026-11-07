package com.hsf.aichat.config;

import com.hsf.aichat.repository.KnowledgeDocumentRepository;
import com.hsf.aichat.service.DocumentService;
import com.hsf.aichat.web.dto.Dtos.UploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Loads the files in src/main/resources/sample-data/ into the RAG knowledge base
 * the first time the app starts with an empty document table, so there is something to test right away.
 * Turn it off with APP_SAMPLE_DATA=false.
 */
@Component
@Order(2) // after ProviderSeeder, so the embedding provider is already set
public class SampleDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentService documentService;
    private final boolean enabled;

    public SampleDataSeeder(KnowledgeDocumentRepository documentRepository, DocumentService documentService,
                            @Value("${app.sample-data:true}") boolean enabled) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!enabled || documentRepository.count() > 0) {
            return;
        }
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:sample-data/*.md");
        for (Resource file : files) {
            String text = file.getContentAsString(StandardCharsets.UTF_8);
            UploadResponse result = documentService.addText(file.getFilename(), text);
            log.info("Sample document {} loaded ({} chunks){}", file.getFilename(), result.document().chunkCount(),
                    result.warning() == null ? "" : " - " + result.warning());
        }
    }
}
