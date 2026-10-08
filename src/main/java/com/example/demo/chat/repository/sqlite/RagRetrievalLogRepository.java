package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.RagRetrievalLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLog, Long> {
    List<RagRetrievalLog> findByUserIdOrderByCreatedAtDesc(String userId);
    List<RagRetrievalLog> findByConversationIdOrderByCreatedAtDesc(String conversationId);
    List<RagRetrievalLog> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);
    Optional<RagRetrievalLog> findByTraceId(String traceId);
}
