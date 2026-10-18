package com.example.demo.chat;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompressionServiceTest {

    private static final String FIRST = "这是首句，介绍浇水的基本原则。\n";
    private static final String NUMERIC = "每次浇水约200毫升，每3天一次。\n";
    private static final String SAFETY = "如果发现烂根要立即停水并通风。\n";
    private static final String OPTIONAL_A = "另外可以适当松土帮助透气。\n";
    private static final String OPTIONAL_B = "花盆选择透气性好的陶盆。";
    private static final String CONTENT = FIRST + NUMERIC + SAFETY + OPTIONAL_A + OPTIONAL_B;

    @Test
    void keepsProtectedSentencesAndDropsLowValueOptions() {
        ContextCompressionService service = service(true, "extractive", 70, 10, "");
        ContextCompressionService.CompressionResult result =
                service.compress("user-a", "浇水 烂根", List.of(publicChunk(CONTENT)));

        String compressed = result.results().get(0).getContent();
        assertTrue(compressed.contains(FIRST));
        assertTrue(compressed.contains(NUMERIC));
        assertTrue(compressed.contains(SAFETY));
        assertTrue(compressed.length() < CONTENT.length());
        assertEquals(FIRST + NUMERIC + SAFETY + OPTIONAL_A, compressed);

        JSONObject stats = result.stats().toJson();
        assertEquals(true, stats.getBoolean("compressionApplied"));
        assertEquals(5, stats.getIntValue("compressionProtectedFacts"));
        assertEquals(5, stats.getIntValue("compressionPreservedProtectedFacts"));
        assertEquals(1, stats.getIntValue("compressionCompressedCount"));
        assertTrue(stats.getIntValue("compressionAfterChars")
                < stats.getIntValue("compressionBeforeChars"));
    }

    @Test
    void returnsOriginalWhenMandatorySentencesExceedBudget() {
        ContextCompressionService service = service(true, "extractive", 30, 10, "");
        ContextCompressionService.CompressionResult result =
                service.compress("user-a", "浇水", List.of(publicChunk(CONTENT)));

        assertEquals(CONTENT, result.results().get(0).getContent());
        JSONObject stats = result.stats().toJson();
        assertEquals(false, stats.getBoolean("compressionApplied"));
        assertEquals(1, stats.getIntValue("compressionSkippedCount"));
        assertEquals(5, stats.getIntValue("compressionProtectedFacts"));
        assertEquals(5, stats.getIntValue("compressionPreservedProtectedFacts"));
    }

    @Test
    void compressesOnlyPublicChunksAndPreservesMemoryVerbatim() {
        ContextCompressionService service = service(true, "extractive", 70, 10, "");
        VectorStoreService.SearchResult memory = memoryChunk(CONTENT);

        ContextCompressionService.CompressionResult result =
                service.compress("user-a", "浇水", List.of(memory, publicChunk(CONTENT)));

        assertSame(memory, result.results().get(0));
        assertEquals(CONTENT, result.results().get(0).getContent());
        assertTrue(result.results().get(1).getContent().length() < CONTENT.length());
        assertEquals(1, result.stats().toJson().getIntValue("compressionChunkCount"));
    }

    @Test
    void preservesSourceIdRankAndMetadataAfterCompression() {
        ContextCompressionService service = service(true, "extractive", 70, 10, "");
        JSONObject metadata = new JSONObject();
        metadata.put("title", "浇水指南");
        VectorStoreService.SearchResult original = new VectorStoreService.SearchResult(
                "doc-1", "kb_water", CONTENT, 0.9, metadata,
                VectorStoreService.ORIGIN_PUBLIC_KB, 0.2, "both", 1, 2, 0.88);

        VectorStoreService.SearchResult compressed = service
                .compress("user-a", "浇水", List.of(original))
                .results().get(0);

        assertEquals("doc-1", compressed.getDocumentId());
        assertEquals("kb_water", compressed.getSourceId());
        assertEquals(1, compressed.getRank());
        assertEquals(2, compressed.getCosineRank());
        assertEquals(0.88, compressed.getRerankScore(), 1e-9);
        assertSame(metadata, compressed.getMetadata());
    }

    @Test
    void cacheReusesResultForSameContentAndQuery() {
        ContextCompressionService service = service(true, "extractive", 70, 10, "");
        ContextCompressionService.CompressionResult first =
                service.compress("user-a", "浇水", List.of(publicChunk(CONTENT)));
        ContextCompressionService.CompressionResult second =
                service.compress("user-a", "浇水", List.of(publicChunk(CONTENT)));

        assertEquals(0, first.stats().toJson().getIntValue("compressionCacheHits"));
        assertEquals(1, second.stats().toJson().getIntValue("compressionCacheHits"));
        assertEquals(first.results().get(0).getContent(),
                second.results().get(0).getContent());
    }

    @Test
    void disabledModeReturnsOriginal() {
        ContextCompressionService service = service(false, "extractive", 70, 10, "");
        ContextCompressionService.CompressionResult result =
                service.compress("user-a", "浇水", List.of(publicChunk(CONTENT)));

        assertEquals(CONTENT, result.results().get(0).getContent());
        assertEquals(false, result.stats().toJson().getBoolean("compressionEnabled"));
        assertEquals(false, result.stats().toJson().getBoolean("compressionApplied"));
    }

    @Test
    void unknownModeFallsBackSafely() {
        ContextCompressionService service = service(true, "llm", 70, 10, "");
        ContextCompressionService.CompressionResult result =
                service.compress("user-a", "浇水", List.of(publicChunk(CONTENT)));

        assertEquals(CONTENT, result.results().get(0).getContent());
        assertEquals("unsupported_mode",
                result.stats().toJson().getString("compressionFallbackReason"));
    }

    @Test
    void canaryExcludedUserGetsOriginal() {
        ContextCompressionService service = service(true, "extractive", 70, 10, "someone-else");
        ContextCompressionService.CompressionResult result =
                service.compress("user-a", "浇水", List.of(publicChunk(CONTENT)));

        assertEquals(CONTENT, result.results().get(0).getContent());
        assertEquals("canary_excluded",
                result.stats().toJson().getString("compressionFallbackReason"));
    }

    @Test
    void shortAndSingleSentenceChunksAreLeftUntouched() {
        ContextCompressionService service = service(true, "extractive", 70, 10, "");
        String shortContent = "短内容。";
        String singleSentence = "这是一句很长但没有句号的内容所以不会被切分";

        ContextCompressionService.CompressionResult shortResult =
                service.compress("user-a", "浇水", List.of(publicChunk(shortContent)));
        ContextCompressionService.CompressionResult singleResult =
                service.compress("user-a", "浇水", List.of(publicChunk(singleSentence)));

        assertEquals(shortContent, shortResult.results().get(0).getContent());
        assertEquals(singleSentence, singleResult.results().get(0).getContent());
        assertFalse(shortResult.stats().toJson().getBoolean("compressionApplied"));
        assertFalse(singleResult.stats().toJson().getBoolean("compressionApplied"));
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        ContextCompressionService service = service(true, "extractive", 70, 10, "");

        ContextCompressionService.CompressionResult empty =
                service.compress("user-a", "浇水", List.of());
        ContextCompressionService.CompressionResult nullInput =
                service.compress("user-a", "浇水", null);

        assertTrue(empty.results().isEmpty());
        assertTrue(nullInput.results().isEmpty());
        assertNotNull(empty.stats());
    }

    private ContextCompressionService service(boolean enabled, String mode,
                                              int maxCharsPerChunk, int minCharsToCompress,
                                              String canaryUsers) {
        return new ContextCompressionService(
                enabled, mode, maxCharsPerChunk, minCharsToCompress, 8, true, canaryUsers);
    }

    private VectorStoreService.SearchResult publicChunk(String content) {
        return new VectorStoreService.SearchResult(
                "doc", "kb", content, 0.9, null,
                VectorStoreService.ORIGIN_PUBLIC_KB, 0.0, "vector");
    }

    private VectorStoreService.SearchResult memoryChunk(String content) {
        return new VectorStoreService.SearchResult(
                "doc-memory", null, content, 0.9, null,
                VectorStoreService.ORIGIN_CONVERSATION, 0.0, null);
    }
}
