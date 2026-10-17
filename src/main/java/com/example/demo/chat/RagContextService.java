package com.example.demo.chat;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.VectorStoreService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagContextService {

    private static final Logger logger = LoggerFactory.getLogger(RagContextService.class);

    private final VectorStoreService vectorStoreService;

    public RagContextService(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    public String buildContext(String userId, String conversationId, String query) {
        return buildContextWithTrace(userId, conversationId, query).context();
    }

    public ContextResult buildContextWithTrace(String userId, String conversationId, String query) {
        return buildContextWithTrace(userId, conversationId, query, false);
    }

    /**
     * @param citationEnabled true 时上下文按来源编号，供模型产出内联 [n]；
     *                        false 时保持原有 chunk 编号格式，旧客户端零变化。
     */
    public ContextResult buildContextWithTrace(String userId, String conversationId, String query,
                                               boolean citationEnabled) {
        if (query == null || query.isBlank()) {
            return new ContextResult("", null, List.of());
        }

        try {
            VectorStoreService.SearchOutcome outcome =
                    vectorStoreService.searchSimilarWithMetadataAndTrace(
                            query, userId, conversationId);
            List<SearchResult> results = outcome == null ? null : outcome.results();
            if (results == null || results.isEmpty()) {
                return new ContextResult("", outcome == null ? null : outcome.traceId(), List.of());
            }
            List<SourceRef> sources = buildSourceRefs(results);
            return new ContextResult(formatResults(results, citationEnabled, sources),
                    outcome.traceId(), sources);
        } catch (Exception e) {
            logger.warn("Failed to build RAG context: {}", e.getMessage());
            return new ContextResult("", null, List.of());
        }
    }

    /**
     * 对外可展示的来源：仅公共知识块，按 sourceId 去重并保留相似度最高的一条。
     * rank 从 1 开始，列表顺序即 rank 顺序；不暴露 documentId、正文、相似度或 metadata 原文。
     */
    public record SourceRef(String sourceId, String title, int rank) {
        public SourceRef(String sourceId, String title) {
            this(sourceId, title, 0);
        }
    }

    public record ContextResult(String context, String traceId, List<SourceRef> sources) {
        public ContextResult {
            context = context == null ? "" : context;
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public ContextResult(String context, String traceId) {
            this(context, traceId, List.of());
        }
    }

    private List<SourceRef> buildSourceRefs(List<SearchResult> results) {
        List<SearchResult> candidates = new ArrayList<>();
        for (SearchResult result : results) {
            if (result == null || result.getContent() == null || result.getContent().isBlank()) {
                continue;
            }
            if (!VectorStoreService.ORIGIN_PUBLIC_KB.equals(result.getOrigin())) {
                continue;
            }
            if (result.getSourceId() == null || result.getSourceId().isBlank()) {
                continue;
            }
            candidates.add(result);
        }

        candidates.sort(Comparator.comparingDouble(SearchResult::getSimilarity).reversed());
        Map<String, SourceRef> bySourceId = new LinkedHashMap<>();
        for (SearchResult result : candidates) {
            if (!bySourceId.containsKey(result.getSourceId())) {
                int rank = bySourceId.size() + 1;
                bySourceId.put(result.getSourceId(),
                        new SourceRef(result.getSourceId(), resolveTitle(result), rank));
            }
        }
        return List.copyOf(bySourceId.values());
    }

    private String resolveTitle(SearchResult result) {
        JSONObject metadata = result.getMetadata();
        if (metadata != null) {
            String title = metadata.getString("title");
            if (title != null && !title.isBlank()) {
                return collapseWhitespace(title);
            }
            String fileName = metadata.getString("fileName");
            if (fileName != null && !fileName.isBlank()) {
                String stripped = fileName.replaceFirst("(?i)\\.(md|markdown)$", "").trim();
                if (!stripped.isEmpty()) {
                    return collapseWhitespace(stripped);
                }
            }
        }
        return result.getSourceId();
    }

    private String collapseWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ");
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

    /**
     * 引用模式：每条公共知识块前标注来源编号与标题，历史对话明确标为不可引用。
     * 编号与 SourceRef.rank 严格一致。
     */
    private String formatResults(List<SearchResult> results, boolean citationEnabled,
                                 List<SourceRef> sources) {
        if (!citationEnabled) {
            return formatResults(results);
        }

        Map<String, Integer> rankBySourceId = new HashMap<>();
        for (SourceRef source : sources) {
            rankBySourceId.put(source.sourceId(), source.rank());
        }

        List<PublicChunk> publicChunks = new ArrayList<>();
        List<String> conversationMemory = new ArrayList<>();
        for (SearchResult result : results) {
            if (result == null || result.getContent() == null || result.getContent().isBlank()) {
                continue;
            }
            if (VectorStoreService.ORIGIN_PUBLIC_KB.equals(result.getOrigin())) {
                String sourceId = result.getSourceId();
                if (sourceId == null || sourceId.isBlank()) {
                    continue;
                }
                Integer rank = rankBySourceId.get(sourceId);
                if (rank == null) {
                    continue;
                }
                publicChunks.add(new PublicChunk(rank, resolveTitle(result), result.getContent().trim()));
            } else {
                conversationMemory.add(result.getContent().trim());
            }
        }

        StringBuilder context = new StringBuilder();
        if (!publicChunks.isEmpty()) {
            context.append("公共知识库信息（以下编号可在回答中引用）：");
            for (PublicChunk chunk : publicChunks) {
                context.append("\n\n[")
                        .append(chunk.rank())
                        .append("] ")
                        .append(chunk.title())
                        .append('\n')
                        .append(chunk.content());
            }
        }
        if (!conversationMemory.isEmpty()) {
            if (context.length() > 0) {
                context.append("\n\n");
            }
            context.append("相关历史对话（仅供理解上下文，不可引用）：");
            for (String memory : conversationMemory) {
                context.append("\n- ").append(memory);
            }
        }
        return context.toString().trim();
    }

    private record PublicChunk(int rank, String title, String content) {
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
