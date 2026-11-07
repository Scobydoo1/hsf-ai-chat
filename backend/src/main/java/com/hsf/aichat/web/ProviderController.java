package com.hsf.aichat.web;

import com.hsf.aichat.service.ProviderService;
import com.hsf.aichat.web.dto.Dtos.ProviderRequest;
import com.hsf.aichat.web.dto.Dtos.ProviderResponse;
import com.hsf.aichat.web.dto.Dtos.TestResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Settings: the AI platforms and their API keys */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProviderResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderResponse create(@Valid @RequestBody ProviderRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ProviderResponse update(@PathVariable Long id, @Valid @RequestBody ProviderRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** Switch the chat platform */
    @PostMapping("/{id}/activate")
    public ProviderResponse activate(@PathVariable Long id) {
        return service.activate(id);
    }

    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable Long id) {
        return service.test(id);
    }
}
