package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.MessageArchive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageArchiveRepository extends JpaRepository<MessageArchive, Long> {

    /**
     * 把 messages 中 id <= maxId 的行复制到归档表。
     * 主键复用原消息 id，INSERT IGNORE 保证重复执行不重复归档。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO messages_archive
              (id, conversation_id, role, content, `timestamp`, sources_json, archived_at)
            SELECT id, conversation_id, role, content, `timestamp`, sources_json, :archivedAt
            FROM messages
            WHERE conversation_id = :cid AND id <= :maxId
            """, nativeQuery = true)
    int archiveThrough(@Param("cid") String cid,
                       @Param("maxId") long maxId,
                       @Param("archivedAt") LocalDateTime archivedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM messages_archive WHERE archived_at < :cutoff LIMIT :batch",
            nativeQuery = true)
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM MessageArchive a WHERE a.conversationId = :cid")
    int deleteByConversationId(@Param("cid") String cid);

    List<MessageArchive> findByConversationIdOrderByIdAsc(String conversationId);

    long countByConversationId(String conversationId);
}
