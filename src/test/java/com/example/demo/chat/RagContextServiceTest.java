package com.example.demo.chat;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagContextServiceTest {

    @Test
    void separatesPublicKnowledgeFromConversationMemory() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-1",
                        List.of(
                                new VectorStoreService.SearchResult(
                                        "public-doc",
                                        "guide-1",
                                        "公共护理知识",
                                        0.95,
                                        null,
                                        VectorStoreService.ORIGIN_PUBLIC_KB
                                ),
                                new VectorStoreService.SearchResult(
                                        "conversation-doc",
                                        null,
                                        "用户: 我的宠物叫豆豆\n助手: 记住了",
                                        0.91,
                                        null,
                                        VectorStoreService.ORIGIN_CONVERSATION
                                )
                        )
                ));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question");
        String context = result.context();

        assertEquals("trace-1", result.traceId());
        assertTrue(context.contains("公共知识库信息 1:\n公共护理知识"));
        assertTrue(context.contains("相关历史对话 1:\n用户: 我的宠物叫豆豆\n助手: 记住了"));
        assertTrue(context.indexOf("公共知识库信息") < context.indexOf("相关历史对话"));
        assertEquals(1, result.sources().size());
        assertEquals("guide-1", result.sources().get(0).sourceId());
        assertEquals("guide-1", result.sources().get(0).title());
    }

    @Test
    void returnsEmptyContextWhenQueryBlank() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        RagContextService service = new RagContextService(vectorStoreService);

        assertEquals("", service.buildContext("user-a", "conversation-a", "  "));
        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void returnsEmptyContextWhenRetrievalFails() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenThrow(new IllegalStateException("vector store unavailable"));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question");

        assertEquals("", result.context());
        assertEquals(null, result.traceId());
        assertTrue(result.sources().isEmpty());
        verify(vectorStoreService).searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a");
    }

    @Test
    void sourceRanksFollowFinalRankOrderInsteadOfCosine() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-rerank",
                        List.of(
                                new VectorStoreService.SearchResult(
                                        "doc-high", "kb_high", "高余弦内容", 0.9,
                                        metadata("高余弦", "kb_high.md"),
                                        VectorStoreService.ORIGIN_PUBLIC_KB,
                                        0.0, "vector", 2, 1, 0.1),
                                new VectorStoreService.SearchResult(
                                        "doc-low", "kb_low", "低余弦内容", 0.6,
                                        metadata("低余弦", "kb_low.md"),
                                        VectorStoreService.ORIGIN_PUBLIC_KB,
                                        0.0, "vector", 1, 2, 0.9)
                        )
                ));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result = service.buildContextWithTrace(
                "user-a", "conversation-a", "question", true);

        assertEquals(List.of("kb_low", "kb_high"),
                result.sources().stream().map(RagContextService.SourceRef::sourceId).toList());
        assertEquals(1, result.sources().get(0).rank());
        assertEquals(2, result.sources().get(1).rank());
        assertTrue(result.context().indexOf("[1] 低余弦") < result.context().indexOf("[2] 高余弦"));
    }

    @Test
    void compressionKeepsCitationRanksAndUpdatesRetrievalStats() {
        String content = "这是首句，介绍浇水的基本原则。\n"
                + "每次浇水约200毫升，每3天一次。\n"
                + "如果发现烂根要立即停水并通风。\n"
                + "另外可以适当松土帮助透气。\n"
                + "花盆选择透气性好的陶盆。";
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "浇水", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-compress",
                        List.of(
                                new VectorStoreService.SearchResult(
                                        "doc-a", "kb_a", content, 0.9,
                                        metadata("浇水指南", "kb_a.md"),
                                        VectorStoreService.ORIGIN_PUBLIC_KB,
                                        0.0, "vector", 1, 1, 0.9),
                                new VectorStoreService.SearchResult(
                                        "doc-b", "kb_b", content, 0.8,
                                        metadata("松土指南", "kb_b.md"),
                                        VectorStoreService.ORIGIN_PUBLIC_KB,
                                        0.0, "vector", 2, 2, 0.8)
                        )
                ));
        ContextCompressionService compressionService = new ContextCompressionService(
                true, "extractive", 70, 10, 8, true, "");
        RagRetrievalLogService retrievalLogService = mock(RagRetrievalLogService.class);
        RagContextService service = new RagContextService(
                vectorStoreService, compressionService, retrievalLogService);

        RagContextService.ContextResult result = service.buildContextWithTrace(
                "user-a", "conversation-a", "浇水", true);

        assertEquals(List.of("kb_a", "kb_b"),
                result.sources().stream().map(RagContextService.SourceRef::sourceId).toList());
        assertTrue(result.context().contains("[1] 浇水指南"));
        assertTrue(result.context().contains("[2] 松土指南"));
        assertTrue(result.context().contains("每次浇水约200毫升"));
        assertTrue(result.context().contains("立即停水并通风"));
        assertEquals(true, result.stats().toJson().getBoolean("compressionApplied"));
        verify(retrievalLogService).updateCompressionStats(eq("trace-compress"), any());
    }

    @Test
    void deduplicatesChunksBySourceIdKeepingBestHit() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        JSONObject wateringMeta = metadata("浇水与积水", "kb_plant_watering.md");
        JSONObject soilMeta = metadata("土壤与缺铁", "kb_plant_soil_iron.md");
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-2",
                        List.of(
                                publicResult("doc-1", "kb_plant_watering", "浇水的下篇", 0.90, wateringMeta),
                                publicResult("doc-2", "kb_plant_watering", "浇水的上篇", 0.95, wateringMeta),
                                publicResult("doc-3", "kb_plant_soil_iron", "缺铁表现", 0.93, soilMeta)
                        )));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question");

        assertEquals(2, result.sources().size());
        assertEquals("kb_plant_watering", result.sources().get(0).sourceId());
        assertEquals("浇水与积水", result.sources().get(0).title());
        assertEquals("kb_plant_soil_iron", result.sources().get(1).sourceId());
    }

    @Test
    void citationModeAlignsChunkMarkersWithSourceRanks() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        JSONObject wateringMeta = metadata("浇水与积水", "kb_plant_watering.md");
        JSONObject soilMeta = metadata("土壤与缺铁", "kb_plant_soil_iron.md");
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-cite",
                        List.of(
                                publicResult("doc-1", "kb_plant_watering", "浇水下篇", 0.90, wateringMeta),
                                publicResult("doc-2", "kb_plant_watering", "浇水上篇", 0.95, wateringMeta),
                                publicResult("doc-3", "kb_plant_soil_iron", "缺铁表现", 0.93, soilMeta),
                                new VectorStoreService.SearchResult(
                                        "conversation-doc", null, "用户: 豆豆", 0.80, null,
                                        VectorStoreService.ORIGIN_CONVERSATION)
                        )));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question", true);

        assertEquals(2, result.sources().size());
        assertEquals("kb_plant_watering", result.sources().get(0).sourceId());
        assertEquals(1, result.sources().get(0).rank());
        assertEquals("kb_plant_soil_iron", result.sources().get(1).sourceId());
        assertEquals(2, result.sources().get(1).rank());

        String context = result.context();
        assertTrue(context.contains("公共知识库信息（以下编号可在回答中引用）："));
        assertTrue(context.contains("[1] 浇水与积水\n浇水上篇"));
        assertTrue(context.contains("[2] 土壤与缺铁\n缺铁表现"));
        assertTrue(context.contains("[1] 浇水与积水\n浇水下篇"));
        assertTrue(context.contains("相关历史对话（仅供理解上下文，不可引用）："));
        assertTrue(context.contains("- 用户: 豆豆"));
        assertFalse(context.contains("公共知识库信息 1:"));
    }

    @Test
    void citationModeSkipsPublicChunksWithoutSourceId() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-no-source",
                        List.of(
                                publicResult("doc-x", null, "无来源公共块", 0.99, null),
                                new VectorStoreService.SearchResult(
                                        "conversation-doc", null, "用户: 豆豆", 0.80, null,
                                        VectorStoreService.ORIGIN_CONVERSATION)
                        )));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question", true);

        assertTrue(result.sources().isEmpty());
        assertFalse(result.context().contains("公共知识库信息"));
        assertTrue(result.context().contains("相关历史对话（仅供理解上下文，不可引用）："));
        assertFalse(result.context().contains("[1]"));
    }

    @Test
    void citationModeDisabledKeepsLegacyChunkNumbering() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-legacy",
                        List.of(publicResult("doc-1", "kb_water", "浇水知识", 0.95,
                                metadata("浇水与积水", "kb_plant_watering.md")))));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question", false);

        assertEquals("公共知识库信息 1:\n浇水知识", result.context());
        assertFalse(result.context().contains("以下编号可在回答中引用"));
    }

    @Test
    void fallsBackToFileNameThenSourceId() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-3",
                        List.of(
                                publicResult("doc-1", "kb_title", "有标题", 0.95,
                                        metadata("中文标题", "kb_title.md")),
                                publicResult("doc-2", "kb_file", "只有文件名", 0.94,
                                        metadata(null, "kb_file.md")),
                                publicResult("doc-3", "kb_plain", "什么都没有", 0.93, null)
                        )));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question");

        assertEquals("中文标题", result.sources().get(0).title());
        assertEquals("kb_file", result.sources().get(1).title());
        assertEquals("kb_plain", result.sources().get(2).title());
    }

    @Test
    void skipsConversationMemoryAndBlankContent() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadataAndTrace(
                "question", "user-a", "conversation-a"))
                .thenReturn(new VectorStoreService.SearchOutcome(
                        "trace-4",
                        List.of(
                                publicResult("doc-blank", "kb_blank", "   ", 0.99, null),
                                new VectorStoreService.SearchResult(
                                        "conversation-doc", null, "用户: 豆豆", 0.98, null,
                                        VectorStoreService.ORIGIN_CONVERSATION),
                                publicResult("doc-ok", "kb_ok", "可用知识", 0.90, null)
                        )));
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "question");

        assertEquals(1, result.sources().size());
        assertEquals("kb_ok", result.sources().get(0).sourceId());
        assertTrue(result.context().contains("相关历史对话"));
    }

    @Test
    void returnsEmptySourcesWhenQueryBlank() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        RagContextService service = new RagContextService(vectorStoreService);

        RagContextService.ContextResult result =
                service.buildContextWithTrace("user-a", "conversation-a", "  ");

        assertTrue(result.sources().isEmpty());
        verifyNoInteractions(vectorStoreService);
    }

    private VectorStoreService.SearchResult publicResult(String documentId, String sourceId,
                                                         String content, double similarity,
                                                         JSONObject metadata) {
        return new VectorStoreService.SearchResult(
                documentId, sourceId, content, similarity, metadata,
                VectorStoreService.ORIGIN_PUBLIC_KB);
    }

    private JSONObject metadata(String title, String fileName) {
        JSONObject metadata = new JSONObject();
        if (title != null) {
            metadata.put("title", title);
        }
        if (fileName != null) {
            metadata.put("fileName", fileName);
        }
        return metadata;
    }
}
