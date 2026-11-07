package com.hsf.aichat.ai;

final class ChatMessageRoles {

    private ChatMessageRoles() {
    }

    static boolean isAssistant(String role) {
        return "assistant".equalsIgnoreCase(role);
    }
}
