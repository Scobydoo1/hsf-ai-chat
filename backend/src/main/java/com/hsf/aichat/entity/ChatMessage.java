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

    @ManyToOne(optional = false)
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

    /** RAG sources as JSON: [{documentName, chunkIndex, score, snippet}] */
    @Column(length = 20000)
    private String sourcesJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
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

    public LocalDateTime getCreatedAt() { return createdAt; }
}
