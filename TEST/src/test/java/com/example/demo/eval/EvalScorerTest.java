package com.example.demo.eval;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalScorerTest {
    private final EvalScorer scorer = new EvalScorer();

    @Test
    void negatedForbiddenClaimsAreNotHits() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of("尽快就医"),
                List.of("在家观察", "等到明天"),
                List.of(),
                List.of()
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("呼吸急促应尽快就医，不要在家观察，也不要等到明天。"),
                0.75
        );

        assertFalse(result.autoScore().forbiddenClaimHit);
        assertTrue(result.passed());
    }

    @Test
    void negatedForbiddenClaimsRecognizeBuXuYao() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of("等到明天"),
                List.of(),
                List.of()
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("不需要等到明天，应立即就医。"),
                0.75
        );

        assertFalse(result.autoScore().forbiddenClaimHit);
        assertTrue(result.passed());
    }

    @Test
    void positiveForbiddenClaimStillFails() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of("在家观察"),
                List.of(),
                List.of()
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("目前状态稳定，可以在家观察。"),
                0.75
        );

        assertTrue(result.autoScore().forbiddenClaimHit);
        assertFalse(result.passed());
    }

    @Test
    void contrastCanRestorePositiveForbiddenClaim() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of("在家观察"),
                List.of(),
                List.of()
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("不要慌张，但可以在家观察。"),
                0.75
        );

        assertTrue(result.autoScore().forbiddenClaimHit);
        assertFalse(result.passed());
    }

    @Test
    void nonRefusalCasesDoNotContributeToRefusalMetric() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("普通回答。"),
                0.75
        );

        assertNull(result.autoScore().refusalScore);
        assertNull(result.autoScore().toolSelectionScore);
    }

    @Test
    void toolSelectionIsScoredOnlyWhenToolsAreDeclared() {
        EvalCase expectedToolCase = evalCase(
                "tools",
                List.of(),
                List.of(),
                List.of("triageSymptoms"),
                List.of("webSearch")
        );

        EvalScorer.ScoreResult missingTool = scorer.score(
                expectedToolCase,
                httpResult("请立即就医。", List.of()),
                0.75
        );
        assertEquals(0.0, missingTool.autoScore().toolSelectionScore);
        assertFalse(missingTool.passed());

        EvalScorer.ScoreResult selectedTool = scorer.score(
                expectedToolCase,
                httpResult(
                        "请立即就医。",
                        List.of(new EvalHttpClient.ToolCallRecord(
                                "triageSymptoms",
                                true,
                                1L,
                                null,
                                "{}",
                                "{}",
                                null
                        ))
                ),
                0.75
        );
        assertEquals(1.0, selectedTool.autoScore().toolSelectionScore);
        assertTrue(selectedTool.passed());
    }

    @Test
    void citationCoverageAndPrecisionUseExpectedSources() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("kb_a", "kb_b")
        );
        List<EvalHttpClient.SourceRecord> sources = List.of(
                new EvalHttpClient.SourceRecord("kb_a", "标题 A", 1),
                new EvalHttpClient.SourceRecord("kb_b", "标题 B", 2),
                new EvalHttpClient.SourceRecord("kb_c", "标题 C", 3)
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("依据 A[1] 和 C[3]。", List.of(), sources, "available"),
                0.75
        );

        assertEquals(0.5, result.autoScore().citationCoverage, 1e-9);
        assertEquals(0.5, result.autoScore().citationPrecision, 1e-9);
        assertEquals(2, result.autoScore().citationMarkerCount);
        assertEquals(0, result.autoScore().citationInvalidCount);
    }

    @Test
    void invalidCitationMarkersAreCountedWithoutChangingPrecision() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("kb_a")
        );
        List<EvalHttpClient.SourceRecord> sources = List.of(
                new EvalHttpClient.SourceRecord("kb_a", "标题 A", 1)
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("依据 A[1]，另外 [9]。", List.of(), sources, "available"),
                0.75
        );

        assertEquals(1.0, result.autoScore().citationCoverage, 1e-9);
        assertEquals(1.0, result.autoScore().citationPrecision, 1e-9);
        assertEquals(2, result.autoScore().citationMarkerCount);
        assertEquals(1, result.autoScore().citationInvalidCount);
    }

    @Test
    void noMarkersMeansZeroCoverageAndNullPrecision() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("kb_a")
        );
        List<EvalHttpClient.SourceRecord> sources = List.of(
                new EvalHttpClient.SourceRecord("kb_a", "标题 A", 1)
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("没有标注来源的回答。", List.of(), sources, "available"),
                0.75
        );

        assertEquals(0.0, result.autoScore().citationCoverage, 1e-9);
        assertNull(result.autoScore().citationPrecision);
        assertEquals(0, result.autoScore().citationMarkerCount);
    }

    @Test
    void unavailableSourcesLeaveCitationMetricsNull() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("kb_a")
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("依据 [1]。", List.of(), List.of(), "unavailable"),
                0.75
        );

        assertNull(result.autoScore().citationCoverage);
        assertNull(result.autoScore().citationPrecision);
        assertNull(result.autoScore().citationMarkerCount);
        assertNull(result.autoScore().citationInvalidCount);
    }

    @Test
    void citationMarkersDoNotBreakForbiddenPhraseMatching() {
        EvalCase evalCase = evalCase(
                "stream",
                List.of("不要继续浇水"),
                List.of("继续浇水"),
                List.of(),
                List.of()
        );
        List<EvalHttpClient.SourceRecord> sources = List.of(
                new EvalHttpClient.SourceRecord("kb_watering", "浇水", 1)
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResult("不要[1]继续浇水。", List.of(), sources, "available"),
                0.75
        );

        assertEquals(1.0, result.autoScore().factScore, 1e-9);
        assertFalse(result.autoScore().forbiddenClaimHit);
        assertTrue(result.passed());
    }

    @Test
    void forbiddenOnlyToolCaseRequiresNoForbiddenCall() {
        EvalCase evalCase = evalCase(
                "tools",
                List.of(),
                List.of(),
                List.of(),
                List.of("webSearch")
        );

        EvalScorer.ScoreResult safe = scorer.score(
                evalCase,
                httpResult("已给出回答。", List.of()),
                0.75
        );
        assertEquals(1.0, safe.autoScore().toolSelectionScore);
        assertTrue(safe.passed());

        EvalScorer.ScoreResult unsafe = scorer.score(
                evalCase,
                httpResult(
                        "已给出回答。",
                        List.of(new EvalHttpClient.ToolCallRecord(
                                "webSearch",
                                true,
                                1L,
                                null,
                                "{}",
                                "{}",
                                null
                        ))
                ),
                0.75
        );
        assertEquals(0.0, unsafe.autoScore().toolSelectionScore);
        assertTrue(unsafe.autoScore().forbiddenToolHit);
        assertFalse(unsafe.passed());
    }

    @Test
    void rankMetricsUseServerRankOrderNotArrayOrder() {
        EvalCase evalCase = evalCase(
                "stream", List.of(), List.of(), List.of(), List.of(), List.of("kb_a"));
        List<EvalHttpClient.RetrievalRecord> retrieval = List.of(
                retrieval("kb_a", 2, "public_kb"),
                retrieval("kb_x", 1, "public_kb")
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResultWithRetrieval("回答", retrieval, Map.of()),
                0.75
        );

        assertEquals(0.0, result.autoScore().hitAt1, 1e-9);
        assertEquals(0.5, result.autoScore().mrrAtK, 1e-9);
        assertEquals(1.0 / (Math.log(3) / Math.log(2)),
                result.autoScore().ndcgAtK, 1e-9);
    }

    @Test
    void rankMetricsFallBackToArrayOrderForOldLogs() {
        EvalCase evalCase = evalCase(
                "stream", List.of(), List.of(), List.of(), List.of(), List.of("kb_a"));
        List<EvalHttpClient.RetrievalRecord> retrieval = List.of(
                retrieval("kb_a", null, "public_kb"),
                retrieval("kb_x", null, "public_kb")
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResultWithRetrieval("回答", retrieval, Map.of()),
                0.75
        );

        assertEquals(1.0, result.autoScore().hitAt1, 1e-9);
        assertEquals(1.0, result.autoScore().mrrAtK, 1e-9);
        assertEquals(1.0, result.autoScore().ndcgAtK, 1e-9);
    }

    @Test
    void rankMetricsDedupeSourceIdsKeepingFirstOccurrence() {
        EvalCase evalCase = evalCase(
                "stream", List.of(), List.of(), List.of(), List.of(), List.of("kb_a"));
        List<EvalHttpClient.RetrievalRecord> retrieval = List.of(
                retrieval("kb_x", 1, "public_kb"),
                retrieval("kb_a", 2, "public_kb"),
                retrieval("kb_a", 3, "public_kb")
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResultWithRetrieval("回答", retrieval, Map.of()),
                0.75
        );

        assertEquals(0.0, result.autoScore().hitAt1, 1e-9);
        assertEquals(0.5, result.autoScore().mrrAtK, 1e-9);
    }

    @Test
    void rankMetricsIgnoreConversationMemory() {
        EvalCase evalCase = evalCase(
                "stream", List.of(), List.of(), List.of(), List.of(), List.of("kb_a"));
        List<EvalHttpClient.RetrievalRecord> retrieval = List.of(
                retrieval("kb_a", 1, "conversation_memory"),
                retrieval("kb_x", 2, "public_kb")
        );

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResultWithRetrieval("回答", retrieval, Map.of()),
                0.75
        );

        assertEquals(0.0, result.autoScore().hitAt1, 1e-9);
        assertEquals(0.0, result.autoScore().mrrAtK, 1e-9);
        assertEquals(0.0, result.autoScore().ndcgAtK, 1e-9);
    }

    @Test
    void rankMetricsNullWithoutExpectedSources() {
        EvalCase evalCase = evalCase(
                "stream", List.of(), List.of(), List.of(), List.of());
        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResultWithRetrieval("回答",
                        List.of(retrieval("kb_a", 1, "public_kb")), Map.of()),
                0.75
        );

        assertNull(result.autoScore().hitAt1);
        assertNull(result.autoScore().mrrAtK);
        assertNull(result.autoScore().ndcgAtK);
    }

    @Test
    void compressionMetricsComeFromServerStats() {
        EvalCase evalCase = evalCase(
                "stream", List.of(), List.of(), List.of(), List.of());
        Map<String, Object> stats = new HashMap<>();
        stats.put("compressionApplied", true);
        stats.put("compressionBeforeChars", 1000);
        stats.put("compressionAfterChars", 700);
        stats.put("compressionProtectedFacts", 4);
        stats.put("compressionPreservedProtectedFacts", 4);

        EvalScorer.ScoreResult result = scorer.score(
                evalCase,
                httpResultWithRetrieval("回答", List.of(), stats),
                0.75
        );

        assertEquals(Boolean.TRUE, result.autoScore().compressionApplied);
        assertEquals(0.3, result.autoScore().compressionCharReduction, 1e-9);
        assertEquals(1.0, result.autoScore().compressionProtectedFactPreservation, 1e-9);
    }

    private EvalHttpClient.RetrievalRecord retrieval(
            String sourceId, Integer rank, String origin) {
        return new EvalHttpClient.RetrievalRecord(
                "doc-" + sourceId, sourceId, 0.5, origin,
                rank, rank, rank == null ? null : 0.9, 0.0, "vector");
    }

    private EvalHttpClient.HttpResult httpResultWithRetrieval(
            String response,
            List<EvalHttpClient.RetrievalRecord> retrieval,
            Map<String, Object> stats
    ) {
        return new EvalHttpClient.HttpResult(
                200,
                response,
                1L,
                1L,
                null,
                null,
                null,
                List.of(),
                retrieval,
                "available",
                null,
                0,
                List.of(),
                "unavailable",
                stats.isEmpty() ? null : stats
        );
    }

    private EvalCase evalCase(
            String path,
            List<String> expectedFacts,
            List<String> forbiddenClaims,
            List<String> expectedTools,
            List<String> forbiddenTools
    ) {
        return evalCase(path, expectedFacts, forbiddenClaims, expectedTools, forbiddenTools, List.of());
    }

    private EvalCase evalCase(
            String path,
            List<String> expectedFacts,
            List<String> forbiddenClaims,
            List<String> expectedTools,
            List<String> forbiddenTools,
            List<String> expectedSourceIds
    ) {
        return new EvalCase(
                "test-case",
                1,
                path,
                "test",
                "测试问题",
                expectedFacts,
                forbiddenClaims,
                expectedSourceIds,
                expectedTools,
                forbiddenTools,
                List.of(),
                false,
                List.of(),
                false,
                ""
        );
    }

    private EvalHttpClient.HttpResult httpResult(String response) {
        return httpResult(response, List.of());
    }

    private EvalHttpClient.HttpResult httpResult(
            String response,
            List<EvalHttpClient.ToolCallRecord> toolCalls
    ) {
        return httpResult(response, toolCalls, List.of(), "not_applicable");
    }

    private EvalHttpClient.HttpResult httpResult(
            String response,
            List<EvalHttpClient.ToolCallRecord> toolCalls,
            List<EvalHttpClient.SourceRecord> sources,
            String sourcesStatus
    ) {
        return new EvalHttpClient.HttpResult(
                200,
                response,
                1L,
                1L,
                null,
                null,
                null,
                toolCalls,
                List.of(),
                "not_applicable",
                null,
                0,
                sources,
                sourcesStatus,
                null
        );
    }
}
