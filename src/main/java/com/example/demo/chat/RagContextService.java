package com.example.demo.chat;

import com.example.demo.chat.VectorStoreService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagContextService {

    private static final Logger logger = LoggerFactory.getLogger(RagContextService.class);

    private final VectorStoreService vectorStoreService;

    public RagContextService(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    public String buildContext(String userId, String conversationId, String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        try {
            List<SearchResult> results = vectorStoreService.searchSimilarWithMetadata(
                    query, userId, conversationId);
            if (results == null || results.isEmpty()) {
                return "";
            }
            return formatResults(results);
        } catch (Exception e) {
            logger.warn("Failed to build RAG context: {}", e.getMessage());
            return "";
        }
    }

    private String formatResults(List<SearchResult> results) {
        List<String> publicKnowledge = new ArrayList<>();
        List<String> conversationMemory = new ArrayList<>();

        for (SearchResult result : results) {
            if (result == null || result.getContent() == null || result.getContent().isBlank()) {
                continue;
            }
            if (VectorStoreService.ORIGIN_PUBLIC_KB.equals(result.getOrigin())) {
                publicKnowledge.add(result.getContent().trim());
            } else {
                conversationMemory.add(result.getContent().trim());
            }
        }

        StringBuilder context = new StringBuilder();
        appendSection(context, "公共知识库信息", publicKnowledge);
        appendSection(context, "相关历史对话", conversationMemory);
        return context.toString().trim();
    }

    private void appendSection(StringBuilder context, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        if (context.length() > 0) {
            context.append("\n\n");
        }
        for (int i = 0; i < items.size(); i++) {
            context.append(title)
                    .append(' ')
                    .append(i + 1)
                    .append(":\n")
                    .append(items.get(i))
                    .append('\n');
        }
        context.setLength(context.length() - 1);
    }
}
