package com.example.demo.chat.lifecycle;

public class SummaryApplyException extends RuntimeException {

    public enum Reason {
        CONFLICT,
        STALE,
        CLEARED
    }

    private final Reason reason;

    public SummaryApplyException(String conversationId, Reason reason) {
        super("Summary apply " + reason + " for conversation " + conversationId);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
