package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.RagRetrievalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLog, Long> {
    List<RagRetrievalLog> findByUserIdOrderByCreatedAtDesc(String userId);
    List<RagRetrievalLog> findByConversationIdOrderByCreatedAtDesc(String conversationId);
}
