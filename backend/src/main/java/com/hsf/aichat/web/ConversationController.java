package com.hsf.aichat.web;

import com.hsf.aichat.service.ChatService;
import com.hsf.aichat.web.dto.Dtos.ConversationResponse;
import com.hsf.aichat.web.dto.Dtos.CreateConversationRequest;
import com.hsf.aichat.web.dto.Dtos.MessageResponse;
import com.hsf.aichat.web.dto.Dtos.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ChatService chatService;

    public ConversationController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public List<ConversationResponse> list() {
        return chatService.conversations();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@RequestBody(required = false) CreateConversationRequest request) {
        return chatService.create(request == null ? null : request.mode());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        chatService.delete(id);
    }

    @GetMapping("/{id}/messages")
    public List<MessageResponse> messages(@PathVariable Long id) {
        return chatService.messages(id);
    }

    /** Send a message and get the AI's answer (502 with a "code" if every AI failed; then nothing is saved) */
    @PostMapping("/{id}/messages")
    public MessageResponse send(@PathVariable Long id, @Valid @RequestBody SendMessageRequest request) {
        return chatService.send(id, request.trimmed());
    }
}
