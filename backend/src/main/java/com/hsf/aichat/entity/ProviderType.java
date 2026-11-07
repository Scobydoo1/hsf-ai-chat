package com.hsf.aichat.entity;

/** The kinds of AI platforms the app can talk to. */
public enum ProviderType {
    /** Offline fake AI, no API key needed. Good for first run, demos and tests. */
    DEMO,
    /** OpenAI and every API that copies its format: Groq, OpenRouter, DeepSeek, Ollama, LM Studio... */
    OPENAI_COMPATIBLE,
    /** Google Gemini API (generativelanguage.googleapis.com) */
    GEMINI,
    /** Anthropic Claude, called through the official Anthropic Java SDK */
    ANTHROPIC
}
