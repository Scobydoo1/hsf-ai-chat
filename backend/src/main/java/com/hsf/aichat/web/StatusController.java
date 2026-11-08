package com.hsf.aichat.web;

import com.hsf.aichat.service.EmbeddingMaintenance;
import com.hsf.aichat.service.StatusSnapshot;
import com.hsf.aichat.web.dto.Dtos.StatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint (no password): the front-end calls it first and every 9 minutes to keep the server awake,
 * and Render uses it as a health check. It never touches the database, so the free database can sleep.
 */
@RestController
public class StatusController {

    private final StatusSnapshot snapshot;
    private final EmbeddingMaintenance embeddingMaintenance;
    private final boolean passwordRequired;

    public StatusController(StatusSnapshot snapshot, EmbeddingMaintenance embeddingMaintenance,
                            @Value("${app.password:}") String password) {
        this.snapshot = snapshot;
        this.embeddingMaintenance = embeddingMaintenance;
        this.passwordRequired = !password.isBlank();
    }

    @GetMapping("/api/status")
    public StatusResponse status() {
        StatusSnapshot.Providers providers = snapshot.providers(); // in memory
        return new StatusResponse("ok", passwordRequired, providers.activeName(), providers.activeModel(),
                providers.embeddingName(), embeddingMaintenance.isRunning());
    }
}
