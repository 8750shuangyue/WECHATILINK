package com.example.demo.chat;

import java.util.Set;

/** 内存索引里一条向量的元数据；tokens 在入库时预计算供关键词通道复用。 */
final class IndexEntry {

    final String documentId;
    final String content;
    final String userId;
    final String conversationId;
    final String sourceId;
    final String metadataJson;
    final Set<String> tokens;

    IndexEntry(String documentId, String content, String userId, String conversationId,
               String sourceId, String metadataJson) {
        this.documentId = documentId;
        this.content = content;
        this.userId = userId;
        this.conversationId = conversationId;
        this.sourceId = sourceId;
        this.metadataJson = metadataJson;
        this.tokens = RetrievalScorer.tokens(content);
    }

    boolean isPublicKnowledge() {
        return sourceId != null && !sourceId.isEmpty()
                && (conversationId == null || conversationId.isEmpty());
    }
}
