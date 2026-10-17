package com.example.demo.chat.lifecycle;

/**
 * 一次摘要租约的快照。version 是 claim 之后的版本，apply 时必须仍然匹配。
 */
public record SummaryClaim(
        String conversationId,
        String oldSummary,
        long coveredUntilId,
        long version,
        int attemptCount
) {
}
