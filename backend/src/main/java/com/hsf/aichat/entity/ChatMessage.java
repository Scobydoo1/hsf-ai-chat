package com.hsf.aichat.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LAZY: loading the messages of a chat does not need to load the chat again */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    /** "user" or "assistant" */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, length = 100000)
    private String content;

    /** Which provider/model wrote this answer (shows the platform switch in the UI) */
    @Column(length = 100)
    private String providerName;

    @Column(length = 100)
    private String model;

    /** RAG sources as JSON: [{documentId, documentName, chunkIndex, score, method, snippet}] */
    @Column(length = 20000)
    private String sourcesJson;

    /** Why another AI answered, or a note about the document search; null = nothing special */
    @Column(length = 500)
    private String notice;

    /** Search + AI time of an answer, in milliseconds (null on questions and old answers) */
    private Long durationMs;

    /** How many documents were searched for this answer; null = documents were not used */
    private Integer documentsSearched;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public ChatMessage() {
    }

    public ChatMessage(Conversation conversation, String role, String content) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
    }

    public Long getId() { return id; }
    public Conversation getConversation() { return conversation; }
    public String getRole() { return role; }
    public String getContent() { return content; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSourcesJson() { return sourcesJson; }
    public void setSourcesJson(String sourcesJson) { this.sourcesJson = sourcesJson; }

    public String getNotice() { return notice; }
    public void setNotice(String notice) { this.notice = notice; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Integer getDocumentsSearched() { return documentsSearched; }
    public void setDocumentsSearched(Integer documentsSearched) { this.documentsSearched = documentsSearched; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    /** Set it to keep the time the question was asked (it is saved only after the AI answered) */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
