package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.RagRetrievalLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLog, Long> {
    List<RagRetrievalLog> findByUserIdOrderByCreatedAtDesc(String userId);
    List<RagRetrievalLog> findByConversationIdOrderByCreatedAtDesc(String conversationId);
    List<RagRetrievalLog> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);
    Optional<RagRetrievalLog> findByTraceId(String traceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RagRetrievalLog log where log.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
