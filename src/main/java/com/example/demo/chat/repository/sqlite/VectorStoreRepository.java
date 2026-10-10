package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.VectorStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VectorStoreRepository extends JpaRepository<VectorStore, Long> {
    Optional<VectorStore> findByDocumentId(String documentId);
    List<VectorStore> findBySourceId(String sourceId);
    List<VectorStore> findByConversationId(String conversationId);
    List<VectorStore> findByUserIdAndConversationId(String userId, String conversationId);
    List<VectorStore> findByConversationIdAndUserIdIsNull(String conversationId);
    void deleteByDocumentId(String documentId);
    void deleteBySourceId(String sourceId);
    void deleteByConversationId(String conversationId);
    void deleteByUserIdAndConversationId(String userId, String conversationId);
    long countBySourceId(String sourceId);

    @Query("""
            select distinct v.sourceId
            from VectorStore v
            where v.sourceId is not null
              and v.sourceId <> ''
              and (v.conversationId is null or v.conversationId = '')
            """)
    List<String> findDistinctPublicSourceIds();

    @Query("""
            select v
            from VectorStore v
            where v.conversationId is not null
              and v.conversationId <> ''
              and (v.sourceId is null or v.sourceId = '')
            order by v.id asc
            """)
    List<VectorStore> findConversationVectors();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from VectorStore v
            where v.timestamp < :cutoff
              and v.conversationId is not null
              and v.conversationId <> ''
              and (v.sourceId is null or v.sourceId = '')
            """)
    int deleteExpiredConversationVectors(@Param("cutoff") LocalDateTime cutoff);
}
