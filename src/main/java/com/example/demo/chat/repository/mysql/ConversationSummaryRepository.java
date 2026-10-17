package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.ConversationSummary;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ConversationSummary s WHERE s.conversationId = :cid")
    Optional<ConversationSummary> findForUpdate(@Param("cid") String cid);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE conversation_summaries
               SET summary_text = :summary,
                   covered_until_id = :watermark,
                   status = 'READY',
                   version = version + 1,
                   last_error = NULL,
                   last_attempt_at = :now,
                   next_retry_at = NULL,
                   updated_at = :now
             WHERE conversation_id = :cid AND version = :expectedVersion
            """, nativeQuery = true)
    int markReady(@Param("cid") String cid,
                  @Param("expectedVersion") long expectedVersion,
                  @Param("summary") String summary,
                  @Param("watermark") long watermark,
                  @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE conversation_summaries
               SET status = 'READY',
                   version = version + 1,
                   last_attempt_at = :now,
                   updated_at = :now
             WHERE conversation_id = :cid AND version = :expectedVersion
            """, nativeQuery = true)
    int markNoCandidate(@Param("cid") String cid,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE conversation_summaries
               SET status = 'FAILED',
                   version = version + 1,
                   last_error = :error,
                   last_attempt_at = :now,
                   next_retry_at = :nextRetryAt,
                   updated_at = :now
             WHERE conversation_id = :cid AND version = :expectedVersion
            """, nativeQuery = true)
    int markFailed(@Param("cid") String cid,
                   @Param("expectedVersion") long expectedVersion,
                   @Param("error") String error,
                   @Param("now") LocalDateTime now,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE conversation_summaries
               SET status = 'FAILED',
                   version = version + 1,
                   last_error = 'summary lease expired',
                   last_attempt_at = :now,
                   next_retry_at = :nextRetryAt,
                   updated_at = :now
             WHERE status = 'PENDING' AND last_attempt_at < :staleBefore
            """, nativeQuery = true)
    int markStalePendingFailed(@Param("staleBefore") LocalDateTime staleBefore,
                               @Param("now") LocalDateTime now,
                               @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Query(value = """
            SELECT conversation_id FROM conversation_summaries
             WHERE status = 'FAILED' AND next_retry_at IS NOT NULL AND next_retry_at <= :now
             ORDER BY next_retry_at
             LIMIT :limit
            """, nativeQuery = true)
    List<String> findDueRetries(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Query(value = """
            SELECT conversation_id FROM conversation_summaries
             WHERE status = 'PENDING' AND last_attempt_at < :staleBefore
             LIMIT :limit
            """, nativeQuery = true)
    List<String> findStalePending(@Param("staleBefore") LocalDateTime staleBefore,
                                  @Param("limit") int limit);

    /**
     * 丢失事件对账：active 中超出水位线的 user/assistant 消息数超过窗口，
     * 且摘要不在租约中、也不在失败退避中。
     */
    @Query(value = """
            SELECT m.conversation_id
              FROM messages m
              LEFT JOIN conversation_summaries s ON s.conversation_id = m.conversation_id
             WHERE m.role IN ('user','assistant')
               AND m.id > COALESCE(s.covered_until_id, 0)
               AND (s.conversation_id IS NULL
                    OR (s.status <> 'PENDING'
                        AND (s.status <> 'FAILED'
                             OR s.next_retry_at IS NULL
                             OR s.next_retry_at <= :now)))
             GROUP BY m.conversation_id
            HAVING COUNT(*) > :window
             LIMIT :limit
            """, nativeQuery = true)
    List<String> findReconciliationCandidates(@Param("window") int window,
                                              @Param("now") LocalDateTime now,
                                              @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ConversationSummary s WHERE s.conversationId = :cid")
    int deleteByConversationId(@Param("cid") String cid);
}
