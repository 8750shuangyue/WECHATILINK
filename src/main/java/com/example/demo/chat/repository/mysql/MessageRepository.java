package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.Message;
import com.example.demo.chat.entity.MessageProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdOrderByTimestampAsc(String conversationId);
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
