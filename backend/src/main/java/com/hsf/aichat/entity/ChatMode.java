package com.hsf.aichat.entity;

public enum ChatMode {
    /** Plain chat with the external AI */
    CHAT,
    /** Retrieval-Augmented Generation: the AI answers from your uploaded documents */
    RAG
}
