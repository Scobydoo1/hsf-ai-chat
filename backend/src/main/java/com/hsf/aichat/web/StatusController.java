package com.hsf.aichat.web;

import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.service.ProviderService;
import com.hsf.aichat.web.dto.Dtos.StatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** Public endpoint (no password): the front-end calls it first, and Render can use it as a health check. */
@RestController
public class StatusController {

    private final ProviderService providerService;
    private final boolean passwordRequired;

    public StatusController(ProviderService providerService, @Value("${app.password:}") String password) {
        this.providerService = providerService;
        this.passwordRequired = !password.isBlank();
    }

    @GetMapping("/api/status")
    public StatusResponse status() {
        Optional<ProviderConfig> active = providerService.active();
        return new StatusResponse("ok", passwordRequired,
                active.map(ProviderConfig::getName).orElse(null),
                active.map(ProviderConfig::getModel).orElse(null),
                providerService.embeddingProvider().map(ProviderConfig::getName).orElse(null));
    }
}
