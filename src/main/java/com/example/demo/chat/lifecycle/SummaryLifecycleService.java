package com.example.demo.chat.lifecycle;

import com.example.demo.chat.CitationMarkers;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 增量摘要编排：claim 租约 -> 事务外 LLM -> 事务内归档/删除/摘要提交。
 *
 * <p>摘要输入 = 旧摘要 + 水位线之后、最近窗口之前的对话。
 * 水位线落在最后一个 assistant 消息上，避免拆散一轮 user/assistant。</p>
 */
@Service
public class SummaryLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(SummaryLifecycleService.class);
    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是一个专业的对话摘要助手，请将对话内容压缩为简明扼要的摘要。";

    private final SummaryStateStore stateStore;
    private final SummaryCompactionWriter compactionWriter;
    private final MessageRepository messageRepository;
    private final LlmService llmService;

    @Value("${chat.memory.summary-keep-recent:5}")
    private int summaryKeepRecent = 5;

    @Value("${app.message-lifecycle.max-messages-per-run:200}")
    private int maxMessagesPerRun = 200;

    @Value("${app.message-lifecycle.summary-lock-ttl-seconds:600}")
    private long lockTtlSeconds = 600;

    @Value("${app.message-lifecycle.summary-max-attempts:10}")
    private int maxAttempts = 10;

    @Value("${app.message-lifecycle.summary-retry-base-delay-seconds:30}")
    private long retryBaseDelaySeconds = 30;

    @Value("${app.message-lifecycle.summary-retry-max-delay-seconds:21600}")
    private long retryMaxDelaySeconds = 21600;

    public SummaryLifecycleService(SummaryStateStore stateStore,
                                   SummaryCompactionWriter compactionWriter,
                                   MessageRepository messageRepository,
                                   @Lazy LlmService llmService) {
        this.stateStore = stateStore;
        this.compactionWriter = compactionWriter;
        this.messageRepository = messageRepository;
        this.llmService = llmService;
    }

    public void summarize(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        int window = Math.max(2, summaryKeepRecent * 2);
        long currentWatermark = stateStore.coveredUntilId(conversationId);

        // 先做无锁预检，避免每条消息都写一次 PENDING。
        List<Message> preview = messageRepository.findActiveAfter(
                conversationId, currentWatermark, PageRequest.of(0, window + maxMessagesPerRun));
        int previewCompactSize = Math.min(preview.size() - window, maxMessagesPerRun);
        if (previewCompactSize <= 0
                || lastAssistantIndex(preview, 0, previewCompactSize) < 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        SummaryClaim claim;
        try {
            claim = stateStore.claim(conversationId, now, Duration.ofSeconds(lockTtlSeconds));
        } catch (Exception exception) {
            // 并发插入摘要行等场景按 BUSY 处理，下一轮事件/对账会重试。
            logger.debug("Summary claim failed for conversation {}: {}",
                    conversationId, exception.getMessage());
            return;
        }
        if (claim == null) {
            return;
        }

        try {
            List<Message> active = messageRepository.findActiveAfter(
                    conversationId, claim.coveredUntilId(),
                    PageRequest.of(0, window + maxMessagesPerRun));
            if (active.size() <= window) {
                stateStore.markNoCandidate(claim, LocalDateTime.now());
                return;
            }

            int compactSize = Math.min(active.size() - window, maxMessagesPerRun);
            List<Message> slice = new ArrayList<>(active.subList(0, compactSize));
            int watermarkIndex = lastAssistantIndex(slice, 0, slice.size());
            if (watermarkIndex < 0) {
                stateStore.markNoCandidate(claim, LocalDateTime.now());
                return;
            }

            List<Message> compacted = slice.subList(0, watermarkIndex + 1);
            long newWatermark = compacted.get(compacted.size() - 1).getId();
            if (newWatermark <= claim.coveredUntilId()) {
                stateStore.markNoCandidate(claim, LocalDateTime.now());
                return;
            }

            String prompt = buildSummaryPrompt(claim.oldSummary(), compacted);
            String summary = llmService.chat(prompt, SUMMARY_SYSTEM_PROMPT);
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("LLM returned an empty summary");
            }

            compactionWriter.apply(claim, summary.trim(), newWatermark, compacted.size(),
                    LocalDateTime.now());
            logger.info("summary_generated userId={} cid={} watermark={} messages={}",
                    userId, conversationId, newWatermark, compacted.size());
        } catch (SummaryApplyException exception) {
            // 另一 worker 已推进、消息被并发清理或会话已清空：交给下一轮，不标记失败。
            logger.info("summary_skipped cid={} reason={}",
                    conversationId, exception.getReason());
        } catch (Exception exception) {
            LocalDateTime failedAt = LocalDateTime.now();
            LocalDateTime nextRetryAt = nextRetryAt(claim.attemptCount(), failedAt);
            stateStore.markFailed(claim, exception.getMessage(), failedAt, nextRetryAt);
            logger.warn("summary_failed cid={} attempt={} nextRetryAt={} error={}",
                    conversationId, claim.attemptCount(), nextRetryAt, exception.getMessage());
        }
    }

    private int lastAssistantIndex(List<Message> messages, int fromIndex, int toIndexExclusive) {
        int from = Math.max(0, fromIndex);
        int to = Math.min(messages.size(), toIndexExclusive);
        for (int index = to - 1; index >= from; index--) {
            if ("assistant".equals(messages.get(index).getRole())) {
                return index;
            }
        }
        return -1;
    }

    private String buildSummaryPrompt(String oldSummary, List<Message> compacted) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("在旧摘要基础上合并新对话。保留核心事实、用户意图、未完成任务、关键数字和时间；")
                .append("旧摘要已有的信息不要重复；不要编造；只输出摘要正文。\n\n");
        if (oldSummary != null && !oldSummary.isBlank()) {
            prompt.append("旧摘要：\n").append(oldSummary.trim()).append("\n\n");
        }
        prompt.append("新对话：\n");
        for (Message message : compacted) {
            String content = "assistant".equals(message.getRole())
                    ? CitationMarkers.strip(message.getContent())
                    : message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            prompt.append("user".equals(message.getRole()) ? "用户: " : "助手: ")
                    .append(content.trim())
                    .append('\n');
        }
        return prompt.toString();
    }

    private LocalDateTime nextRetryAt(int attempt, LocalDateTime now) {
        if (attempt >= maxAttempts) {
            return null;
        }
        long delay = Math.min(retryMaxDelaySeconds,
                retryBaseDelaySeconds * (1L << Math.min(Math.max(attempt, 1) - 1, 20)));
        return now.plusSeconds(delay);
    }
}
