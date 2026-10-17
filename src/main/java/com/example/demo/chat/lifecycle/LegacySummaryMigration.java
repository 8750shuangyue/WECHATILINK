package com.example.demo.chat.lifecycle;

import com.example.demo.chat.entity.ConversationSummary;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.entity.SummaryStatus;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把旧的 messages.role=system + 【对话摘要】 行迁移到 conversation_summaries。
 *
 * <p>旧摘要的 covered_until_id 未知，统一置 0：首次新摘要会把旧摘要和当前 active
 * 旧消息一起输入，宁可多摘要一次，也不跳过未覆盖的消息。旧 system 镜像行保留，
 * 保证旧 AgentService 仍能读取；首次新生命周期压缩时它随 id <= watermark 一起归档。</p>
 */
@Component
public class LegacySummaryMigration {

    private static final Logger logger = LoggerFactory.getLogger(LegacySummaryMigration.class);
    private static final String SUMMARY_PREFIX = "【对话摘要】";

    private final MessageRepository messageRepository;
    private final ConversationSummaryRepository summaryRepository;

    @Value("${app.message-lifecycle.enabled:false}")
    private boolean enabled = false;

    public LegacySummaryMigration(MessageRepository messageRepository,
                                  ConversationSummaryRepository summaryRepository) {
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (!enabled) {
            return;
        }
        List<Message> rows = messageRepository.findSummaryMessages();
        Set<String> seen = new HashSet<>();
        int migrated = 0;
        int skipped = 0;
        int failed = 0;

        for (Message row : rows) {
            String conversationId = row.getConversationId();
            if (conversationId == null || !seen.add(conversationId)) {
                // rows 已按 id DESC 排序，每个会话只处理最新一条摘要。
                continue;
            }
            try {
                if (summaryRepository.existsById(conversationId)) {
                    skipped++;
                    continue;
                }
                String summaryText = extractSummary(row.getContent());
                if (summaryText.isBlank()) {
                    skipped++;
                    continue;
                }
                LocalDateTime now = LocalDateTime.now();
                summaryRepository.save(new ConversationSummary(
                        conversationId, summaryText, 0L, 0L,
                        SummaryStatus.READY, 0, now, now));
                migrated++;
            } catch (Exception exception) {
                failed++;
                logger.warn("Legacy summary migration failed for conversation {}: {}",
                        conversationId, exception.getMessage());
            }
        }

        logger.info("Legacy summary migration completed: migrated={}, skipped={}, failed={}",
                migrated, skipped, failed);
    }

    private String extractSummary(String content) {
        if (content == null) {
            return "";
        }
        String text = content.startsWith(SUMMARY_PREFIX)
                ? content.substring(SUMMARY_PREFIX.length())
                : content;
        return text.replaceFirst("^\\s*\\[更新时间:[^\\]]*\\]\\s*\\n?", "").trim();
    }
}
