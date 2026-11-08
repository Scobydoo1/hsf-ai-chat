package com.hsf.aichat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsf.aichat.ai.AiException;
import com.hsf.aichat.ai.AiMessage;
import com.hsf.aichat.entity.ChatMessage;
import com.hsf.aichat.entity.ChatMode;
import com.hsf.aichat.entity.Conversation;
import com.hsf.aichat.entity.DocumentChunk;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.ChatMessageRepository;
import com.hsf.aichat.repository.ConversationRepository;
import com.hsf.aichat.web.dto.Dtos.ConversationResponse;
import com.hsf.aichat.web.dto.Dtos.MessageResponse;
import com.hsf.aichat.web.dto.Dtos.SendMessageRequest;
import com.hsf.aichat.web.dto.Dtos.SourceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hsf.aichat.web.dto.Dtos.withZone;

/**
 * Sends a message: finds passages in the chosen documents (if any), asks the AI - switching to another AI when
 * the first one fails - and only then saves the question and the answer. If every AI fails, nothing is saved.
 * No database transaction is open while waiting for the AI, so a slow AI never holds a database connection.
 */
@Service
public class ChatService {

    private static final String NEW_CHAT_TITLE = "New chat";

    static final String CHAT_PROMPT = """
            You are a helpful assistant. Answer clearly and concisely. Use Markdown for lists, tables and code.
            Reply in the same language as the user.""";

    static final String RAG_PROMPT = """
            You are a helpful assistant. The user chose some of their documents, and the passages below come from them.
            - When the passages answer the question, base your answer on them and cite each passage you use, like [1] or [2].
            - When they do not, say so in one short sentence, then answer from general knowledge
              and make clear that this part is not from the documents.
            - Never invent facts and never claim that the documents say something they do not say.
            - Reply in the same language as the user. Use Markdown.

            CONTEXT:
            """;

    /** What the document search adds: the system prompt, the sources to show, how many documents, a note */
    private record DocumentContext(String systemPrompt, List<SourceResponse> sources, Integer documentsSearched,
                                   String note) {
    }

    /** The answer, the provider that wrote it, and why the preferred provider did not (null = it did) */
    private record Answer(String text, ProviderConfig provider, String notice) {
    }

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ProviderService providerService;
    private final ProviderHealth health;
    private final RetrievalService retrievalService;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public ChatService(ConversationRepository conversationRepository, ChatMessageRepository messageRepository,
                       ProviderService providerService, ProviderHealth health, RetrievalService retrievalService,
                       TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.providerService = providerService;
        this.health = health;
        this.retrievalService = retrievalService;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public List<ConversationResponse> conversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc().stream().map(ChatService::toResponse).toList();
    }

    public ConversationResponse create(ChatMode mode) {
        ChatMode m = mode == null ? ChatMode.CHAT : mode;
        return toResponse(conversationRepository.save(new Conversation(NEW_CHAT_TITLE, m)));
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

    public MessageResponse send(Long conversationId, SendMessageRequest req) {
        Instant start = Instant.now();
        Conversation conversation = find(conversationId);
        String question = req.content();

        // 1. which AI: the chosen (or active) provider first, then - unless autoFallback is false - the others as backups.
        //    A provider chosen by name ("Ask Groq") is tried even while it rests.
        boolean chosenByUser = req.providerId() != null;
        ProviderConfig preferred = chosenByUser
                ? providerService.get(req.providerId())
                : providerService.active().orElseThrow(() -> new AiException("No AI provider is active. Add one in Settings."));
        List<ProviderConfig> chain = Boolean.FALSE.equals(req.autoFallback())
                ? List.of(preferred)
                : providerService.fallbackChain(preferred, chosenByUser);

        // 2. history: the last 9 saved messages + the new question (not saved yet: if the AI fails, nothing is saved)
        List<ChatMessage> recent = new ArrayList<>(messageRepository.findTop9ByConversationIdOrderByIdDesc(conversationId));
        Collections.reverse(recent); // oldest first
        while (!recent.isEmpty() && ChatMessage.ASSISTANT.equals(recent.get(0).getRole())) {
            recent.remove(0); // the AI must see a question first, not an answer cut from its question
        }
        List<AiMessage> history = new ArrayList<>();
        for (ChatMessage m : recent) {
            history.add(new AiMessage(m.getRole(), m.getContent()));
        }
        history.add(new AiMessage(ChatMessage.USER, question));

        // 3. documents: put the best passages of the chosen documents into the system prompt
        DocumentContext docs = searchDocuments(question, req, conversation);

        // 4. ask the AI, moving to the next provider when one fails
        Answer answer = ask(preferred, chain, docs.systemPrompt(), history);
        long durationMs = Duration.between(start, Instant.now()).toMillis();

        // 5. it worked: save the question and the answer together, in one short transaction
        LocalDateTime askedAt = LocalDateTime.ofInstant(start, ZoneId.systemDefault());
        String notice = joinNotes(answer.notice(), docs.note());
        ChatMessage saved = transactions.execute(status -> {
            Conversation c = find(conversationId); // read it again: the AI call may have taken a while
            ChatMessage asked = new ChatMessage(c, ChatMessage.USER, question);
            asked.setCreatedAt(askedAt);
            messageRepository.save(asked);

            if (NEW_CHAT_TITLE.equals(c.getTitle())) {
                c.setTitle(question.length() > 60 ? question.substring(0, 60) + "…" : question);
            }
            c.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(c);

            ChatMessage reply = new ChatMessage(c, ChatMessage.ASSISTANT, answer.text());
            reply.setProviderName(answer.provider().getName()); // the provider that really answered
            reply.setModel(answer.provider().getModel());
            reply.setSourcesJson(docs.sources().isEmpty() ? null : toJson(docs.sources()));
            reply.setNotice(notice);
            reply.setDurationMs(durationMs);
            reply.setDocumentsSearched(docs.documentsSearched());
            return messageRepository.save(reply);
        });
        return toResponse(saved);
    }

    /**
     * Which documents to search: useDocuments = true -> the chosen ones (none chosen = all), false -> none,
     * null (older clients) -> all of them in a RAG conversation, none in a plain chat.
     */
    private DocumentContext searchDocuments(String question, SendMessageRequest req, Conversation conversation) {
        boolean useDocuments = req.useDocuments() == null
                ? conversation.getMode() == ChatMode.RAG
                : req.useDocuments();
        if (!useDocuments) {
            return new DocumentContext(CHAT_PROMPT, List.of(), null, null);
        }
        RetrievalService.SearchResult result = retrievalService.search(question, chosenDocumentIds(req));
        StringBuilder prompt = new StringBuilder(RAG_PROMPT);
        if (result.hits().isEmpty()) {
            prompt.append("(no matching passages were found in the selected documents)\n");
        }
        List<SourceResponse> sources = new ArrayList<>();
        for (int i = 0; i < result.hits().size(); i++) {
            RetrievalService.Hit hit = result.hits().get(i);
            DocumentChunk chunk = hit.chunk();
            // keep this format "[1] (file name)\n<text>": the Demo provider and the tests read it
            prompt.append("[").append(i + 1).append("] (").append(chunk.getDocument().getName()).append(")\n")
                    .append(chunk.getContent()).append("\n\n");
            sources.add(new SourceResponse(chunk.getDocument().getId(), chunk.getDocument().getName(),
                    chunk.getChunkIndex(), Math.round(hit.score() * 1000) / 1000.0, hit.method(),
                    snippet(chunk.getContent())));
        }
        return new DocumentContext(prompt.toString(), sources, result.documentsSearched(), result.note());
    }

    /** The documents chosen in the request; null = all documents */
    private static List<Long> chosenDocumentIds(SendMessageRequest req) {
        if (req.useDocuments() == null || req.documentIds() == null) {
            return null;
        }
        List<Long> ids = req.documentIds().stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? null : ids;
    }

    /**
     * Tries the providers of the chain in order until one answers. A provider that fails rests for a while
     * (see ProviderHealth), so the next questions skip it. If all fail, throws one error that lists what happened;
     * its code and provider describe the first problem, i.e. the problem of the provider the user chose.
     */
    private Answer ask(ProviderConfig preferred, List<ProviderConfig> chain, String systemPrompt, List<AiMessage> history) {
        List<String> events = new ArrayList<>();  // for the notice: "Google Gemini hit its rate limit (HTTP 429)"
        List<String> summary = new ArrayList<>(); // for the error:  "Google Gemini: rate limit reached (HTTP 429)"
        List<AiException> errors = new ArrayList<>(); // each one knows its provider; the first decides code + provider
        if (!chain.get(0).getId().equals(preferred.getId())) {
            // the preferred provider is resting after an error, so it was skipped
            AiException.Kind kind = health.restingKind(preferred.getId());
            String resting = "resting " + restingReason(kind)
                    + " (about " + Math.max(1, health.cooldownSeconds(preferred.getId())) + " s left)";
            events.add(preferred.getName() + " is " + resting);
            summary.add(preferred.getName() + ": " + resting);
            AiException skipped = new AiException(preferred.getName() + " is " + resting, null, kind, null, null);
            skipped.setProviderName(preferred.getName());
            errors.add(skipped);
        }
        for (ProviderConfig p : chain) {
            try {
                String text = providerService.implementation(p).chat(p, systemPrompt, history);
                health.succeeded(p.getId());
                String notice = events.isEmpty() ? null
                        : joinWithAnd(events) + ", so " + p.getName() + " answered instead.";
                return new Answer(text, p, notice);
            } catch (AiException e) {
                health.failed(p, e);
                e.setProviderName(p.getName());
                errors.add(e);
                events.add(p.getName() + " " + failurePhrase(e));
                summary.add(p.getName() + ": " + e.shortReason());
            }
        }
        // every provider failed. Nothing was saved, so the user can simply try again.
        AiException first = errors.get(0);
        String message = summary.size() == 1 ? first.getMessage() : String.join(" · ", summary);
        AiException error = new AiException(message, first, first.getKind(), first.getHttpStatus(), first.getRetryAfter());
        error.setProviderName(first.getProviderName()); // "code" and "provider" describe the same failure
        throw error;
    }

    /** Completes "Google Gemini ...": "hit its rate limit (HTTP 429)", "is not responding"... */
    private static String failurePhrase(AiException e) {
        return switch (e.getKind()) {
            case RATE_LIMIT -> "hit its rate limit" + (e.getHttpStatus() == null ? "" : " (HTTP " + e.getHttpStatus() + ")");
            case UNAVAILABLE -> "is not responding";
            case AUTH -> e.getHttpStatus() == null ? "has no API key" : "rejected its API key";
            case NOT_FOUND -> "could not find its model";
            case AI_ERROR -> "returned an error";
        };
    }

    /** Completes "Google Gemini is resting ...": "after hitting its limit"... */
    private static String restingReason(AiException.Kind kind) {
        if (kind == null) {
            return "after an error";
        }
        return switch (kind) {
            case RATE_LIMIT -> "after hitting its limit";
            case UNAVAILABLE -> "because it was not responding";
            case AUTH -> "after an API key error";
            case NOT_FOUND -> "because its model was not found";
            case AI_ERROR -> "after an error";
        };
    }

    /** ["A", "B", "C"] -> "A, B and C" */
    private static String joinWithAnd(List<String> parts) {
        int last = parts.size() - 1;
        return last == 0 ? parts.get(0) : String.join(", ", parts.subList(0, last)) + " and " + parts.get(last);
    }

    /** The notes that are not null, joined with a space (null if none); cut to the 500-character column */
    private static String joinNotes(String... notes) {
        String text = Stream.of(notes).filter(Objects::nonNull).collect(Collectors.joining(" "));
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 499) + "…" : text;
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
                withZone(m.getCreatedAt()), m.getNotice(), m.getDurationMs(), m.getDocumentsSearched());
    }

    private static ConversationResponse toResponse(Conversation c) {
        return new ConversationResponse(c.getId(), c.getTitle(), c.getMode(), withZone(c.getCreatedAt()),
                withZone(c.getUpdatedAt()));
    }
}
