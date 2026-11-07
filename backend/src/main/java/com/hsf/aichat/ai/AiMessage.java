package com.hsf.aichat.ai;

/** One turn of the conversation sent to the AI. role = "user" or "assistant". */
public record AiMessage(String role, String content) {
}
