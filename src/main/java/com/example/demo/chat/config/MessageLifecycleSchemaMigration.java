package com.example.demo.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 消息生命周期索引兜底。
 *
 * <p>Hibernate ddl-auto=update 对已有表补索引并不可靠，这里在启动后检查
 * messages(conversation_id, id) 是否存在；缺失时补建。生产发布仍建议提前手工执行 DDL，
 * 本迁移只作为本地/测试和漏执行时的兜底，失败只告警不影响启动。</p>
 */
@Component
public class MessageLifecycleSchemaMigration {

    private static final Logger logger = LoggerFactory.getLogger(MessageLifecycleSchemaMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public MessageLifecycleSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureMessageIndex() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                      FROM INFORMATION_SCHEMA.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'messages'
                       AND INDEX_NAME = 'idx_messages_conv_id'
                    """,
                    Integer.class);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute(
                        "ALTER TABLE messages ADD INDEX idx_messages_conv_id (conversation_id, id)");
                logger.info("Created index idx_messages_conv_id on messages");
            }
        } catch (Exception exception) {
            logger.warn("Failed to ensure messages lifecycle index: {}", exception.getMessage());
        }
    }
}
