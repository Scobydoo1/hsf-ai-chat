package com.hsf.aichat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.ai.AiMessage;
import com.hsf.aichat.entity.ChatMessage;
import com.hsf.aichat.entity.ChatMode;
import com.hsf.aichat.entity.Conversation;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.ChatMessageRepository;
import com.hsf.aichat.repository.ConversationRepository;
import com.hsf.aichat.web.dto.Dtos.ConversationResponse;
import com.hsf.aichat.web.dto.Dtos.MessageResponse;
import com.hsf.aichat.web.dto.Dtos.SourceResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Sends a message: saves it, (in RAG mode) finds document passages, asks the active AI, saves the answer.
 */
@Service
public class ChatService {

    /** How many earlier messages are sent to the AI so it remembers the conversation */
    private static final int HISTORY = 10;

    static final String CHAT_PROMPT = """
            You are a helpful assistant. Answer clearly and concisely. Use Markdown for lists, tables and code.
            Reply in the same language as the user.""";

    static final String RAG_PROMPT = """
            You are an assistant that answers questions using ONLY the context passages below, taken from the user's documents.
            - Cite the passages you use with their number, like [1] or [2].
            - If the answer is not in the context, say that the documents do not contain it. Do not invent facts.
            - Reply in the same language as the user. Use Markdown.

            CONTEXT:
            """;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ProviderService providerService;
    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    public ChatService(ConversationRepository conversationRepository, ChatMessageRepository messageRepository,
                       ProviderService providerService, RetrievalService retrievalService, ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.providerService = providerService;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
    }

    public List<ConversationResponse> conversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc().stream().map(ChatService::toResponse).toList();
    }

    public ConversationResponse create(ChatMode mode) {
        ChatMode m = mode == null ? ChatMode.CHAT : mode;
        return toResponse(conversationRepository.save(new Conversation("New chat", m)));
    }

    public void delete(Long id) {
        Conversation c = find(id);
        messageRepository.deleteByConversationId(c.getId());
        conversationRepository.delete(c);
    }

    public List<MessageResponse> messages(Long conversationId) {
        find(conversationId);
        return messageRepository.findByConversationIdOrderByIdAsc(conversationId).stream().map(this::toResponse).toList();
    }

    public MessageResponse send(Long conversationId, String content) {
        Conversation conversation = find(conversationId);
        ProviderConfig provider = providerService.active()
                .orElseThrow(() -> new AiException("No AI provider is active. Add one in Settings."));

        messageRepository.save(new ChatMessage(conversation, ChatMessage.USER, content));
        if ("New chat".equals(conversation.getTitle())) {
            conversation.setTitle(content.length() > 60 ? content.substring(0, 60) + "…" : content);
        }
        conversation.setUpdatedAt(LocalDateTime.now());
        conversation = conversationRepository.save(conversation);

        // 1. history: the last messages of this conversation (including the new one)
        List<ChatMessage> all = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        List<AiMessage> history = all.subList(Math.max(0, all.size() - HISTORY), all.size()).stream()
                .map(m -> new AiMessage(m.getRole(), m.getContent()))
                .toList();

        // 2. RAG: put the most relevant document passages into the system prompt
        String systemPrompt = CHAT_PROMPT;
        List<SourceResponse> sources = List.of();
        if (conversation.getMode() == ChatMode.RAG) {
            List<RetrievalService.Hit> hits = retrievalService.search(content);
            StringBuilder context = new StringBuilder(RAG_PROMPT);
            if (hits.isEmpty()) {
                context.append("(no relevant passages found - the knowledge base may be empty)\n");
            }
            for (int i = 0; i < hits.size(); i++) {
                RetrievalService.Hit h = hits.get(i);
                context.append("[").append(i + 1).append("] (").append(h.chunk().getDocument().getName()).append(")\n")
                        .append(h.chunk().getContent()).append("\n\n");
            }
            systemPrompt = context.toString();
            sources = hits.stream().map(h -> new SourceResponse(h.chunk().getDocument().getName(),
                    h.chunk().getChunkIndex(), Math.round(h.score() * 1000) / 1000.0, h.method(),
                    snippet(h.chunk().getContent()))).toList();
        }

        // 3. ask the active AI platform
        String answer = providerService.implementation(provider).chat(provider, systemPrompt, history);

        // 4. save the answer with the provider and model that wrote it
        ChatMessage reply = new ChatMessage(conversation, ChatMessage.ASSISTANT, answer);
        reply.setProviderName(provider.getName());
        reply.setModel(provider.getModel());
        reply.setSourcesJson(sources.isEmpty() ? null : toJson(sources));
        return toResponse(messageRepository.save(reply));
    }

    private Conversation find(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Conversation " + id + " not found"));
    }

    private static String snippet(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    private String toJson(List<SourceResponse> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
    }

    private MessageResponse toResponse(ChatMessage m) {
        List<SourceResponse> sources = List.of();
        if (m.getSourcesJson() != null) {
            try {
                sources = objectMapper.readValue(m.getSourcesJson(), new TypeReference<>() {
                });
            } catch (Exception ignored) {
                // old or broken JSON: show the message without sources
            }
        }
        return new MessageResponse(m.getId(), m.getRole(), m.getContent(), m.getProviderName(), m.getModel(), sources,
                m.getCreatedAt());
    }

    private static ConversationResponse toResponse(Conversation c) {
        return new ConversationResponse(c.getId(), c.getTitle(), c.getMode(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
