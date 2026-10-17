package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.Message;
import com.example.demo.chat.entity.MessageHistoryRow;
import com.example.demo.chat.entity.MessageProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdOrderByTimestampAsc(String conversationId);

    List<Message> findByConversationIdOrderByIdAsc(String conversationId);

    List<Message> findByConversationIdAndRoleInOrderByIdDesc(
            String conversationId, Collection<String> roles, Pageable pageable);

    List<Message> findByConversationIdAndRoleInAndIdLessThanOrderByIdDesc(
            String conversationId, Collection<String> roles, Long beforeId, Pageable pageable);

    @Query("""
            SELECT m FROM Message m
             WHERE m.conversationId = :cid
               AND m.role IN ('user','assistant')
               AND m.id > :afterId
             ORDER BY m.id ASC
            """)
    List<Message> findActiveAfter(@Param("cid") String cid,
                                  @Param("afterId") long afterId,
                                  Pageable pageable);

    @Query("""
            SELECT COUNT(m) FROM Message m
             WHERE m.conversationId = :cid
               AND m.role IN ('user','assistant')
               AND m.id > :afterId
               AND m.id <= :maxId
            """)
    long countActiveBetween(@Param("cid") String cid,
                            @Param("afterId") long afterId,
                            @Param("maxId") long maxId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Message m WHERE m.conversationId = :cid AND m.id <= :maxId")
    int deleteCompactedThrough(@Param("cid") String cid, @Param("maxId") long maxId);

    @Query("""
            SELECT m FROM Message m
             WHERE m.role = 'system' AND m.content LIKE '【对话摘要】%'
             ORDER BY m.id DESC
            """)
    List<Message> findSummaryMessages();

    @Query(value = """
            SELECT id, role, content, ts, sources_json FROM (
              SELECT id, role, content, `timestamp` AS ts, sources_json
                FROM messages
               WHERE conversation_id = :cid
                 AND role IN ('user','assistant')
                 AND id < :beforeId
              UNION ALL
              SELECT id, role, content, `timestamp` AS ts, sources_json
                FROM messages_archive
               WHERE conversation_id = :cid
                 AND role IN ('user','assistant')
                 AND id < :beforeId
            ) merged
            ORDER BY id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MessageHistoryRow> findMergedHistory(@Param("cid") String cid,
                                              @Param("beforeId") long beforeId,
                                              @Param("limit") int limit);

    void deleteByConversationId(String conversationId);
    long countByConversationId(String conversationId);
    
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversationId = :conversationId AND m.role = :role AND m.content LIKE CONCAT(:contentPrefix, '%')")
    void deleteByConversationIdAndRoleAndContentStartingWith(
            @Param("conversationId") String conversationId, 
            @Param("role") String role, 
            @Param("contentPrefix") String contentPrefix);
    
    @Query("SELECT m.role AS role, m.content AS content FROM Message m " +
           "WHERE m.conversationId = :conversationId " +
           "ORDER BY m.timestamp ASC LIMIT :limit")
    List<MessageProjection> findMessagesForConversation(@Param("conversationId") String conversationId, 
                                                        @Param("limit") int limit);
}
