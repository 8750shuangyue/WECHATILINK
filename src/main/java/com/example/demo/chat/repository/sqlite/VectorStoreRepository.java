package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.VectorStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VectorStoreRepository extends JpaRepository<VectorStore, Long> {
    Optional<VectorStore> findByDocumentId(String documentId);
    List<VectorStore> findBySourceId(String sourceId);
    List<VectorStore> findByConversationId(String conversationId);
    void deleteByDocumentId(String documentId);
    void deleteBySourceId(String sourceId);
    void deleteByConversationId(String conversationId);
    long countBySourceId(String sourceId);
}