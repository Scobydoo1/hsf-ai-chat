package com.hsf.aichat.ai;

/** Thrown when an AI platform call fails; the message is shown to the user. */
public class AiException extends RuntimeException {

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
