package com.hsf.aichat.web;

import com.hsf.aichat.service.DocumentService;
import com.hsf.aichat.web.dto.Dtos.DocumentResponse;
import com.hsf.aichat.web.dto.Dtos.ReindexResponse;
import com.hsf.aichat.web.dto.Dtos.TextDocumentRequest;
import com.hsf.aichat.web.dto.Dtos.UploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** The RAG knowledge base */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    public List<DocumentResponse> list() {
        return service.list();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(file);
    }

    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse addText(@Valid @RequestBody TextDocumentRequest request) {
        return service.addText(request.name().trim(), request.content());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return service.reindex();
    }
}
