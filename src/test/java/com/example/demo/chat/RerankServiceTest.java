package com.example.demo.chat;

import com.example.demo.config.DashScopeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankServiceTest {

    @Test
    void returnsScoresAlignedByDocumentIndex() {
        RerankService service = service("test-key", 500, 2000,
                (query, documents) -> List.of(0.1, 0.9, 0.5));

        RerankService.RerankOutcome outcome = service.rerank(
                "query", List.of("a", "b", "c"));

        assertTrue(outcome.applied());
        assertEquals(List.of(0.1, 0.9, 0.5), outcome.scoresByIndex());
        assertEquals("gte-rerank-v2", outcome.model());
        service.shutdown();
    }

    @Test
    void fallsBackOnTimeout() {
        RerankService service = service("test-key", 50, 2000, (query, documents) -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(1.0);
        });

        RerankService.RerankOutcome outcome = service.rerank("query", List.of("a"));

        assertFalse(outcome.applied());
        assertEquals(RerankService.FALLBACK_TIMEOUT, outcome.fallbackReason());
        assertTrue(outcome.durationMs() < 1_000);
        service.shutdown();
    }

    @Test
    void fallsBackOnClientException() {
        RerankService service = service("test-key", 500, 2000,
                (query, documents) -> {
                    throw new IllegalStateException("upstream unavailable");
                });

        RerankService.RerankOutcome outcome = service.rerank("query", List.of("a"));

        assertFalse(outcome.applied());
        assertEquals(RerankService.FALLBACK_API_ERROR, outcome.fallbackReason());
        service.shutdown();
    }

    @Test
    void fallsBackOnInvalidResponse() {
        RerankService wrongSize = service("test-key", 500, 2000,
                (query, documents) -> List.of(0.5));
        assertFalse(wrongSize.rerank("query", List.of("a", "b")).applied());
        assertEquals(RerankService.FALLBACK_INVALID_RESPONSE,
                wrongSize.rerank("query", List.of("a", "b")).fallbackReason());
        wrongSize.shutdown();

        RerankService nullScore = service("test-key", 500, 2000,
                (query, documents) -> List.of(0.5, Double.NaN));
        RerankService.RerankOutcome outcome = nullScore.rerank("query", List.of("a", "b"));
        assertFalse(outcome.applied());
        assertEquals(RerankService.FALLBACK_INVALID_RESPONSE, outcome.fallbackReason());
        nullScore.shutdown();
    }

    @Test
    void fallsBackWithoutCallingApiWhenApiKeyMissing() {
        AtomicBoolean called = new AtomicBoolean(false);
        RerankService service = service(null, 500, 2000, (query, documents) -> {
            called.set(true);
            return List.of(1.0);
        });

        RerankService.RerankOutcome outcome = service.rerank("query", List.of("a"));

        assertFalse(outcome.applied());
        assertEquals(RerankService.FALLBACK_MISSING_API_KEY, outcome.fallbackReason());
        assertFalse(called.get());
        service.shutdown();
    }

    @Test
    void truncatesDocumentsToMaxChars() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        RerankService service = service("test-key", 500, 5, (query, documents) -> {
            captured.set(documents);
            return documents.stream().map(ignored -> 1.0).toList();
        });

        service.rerank("query", List.of("abcdefghij", "123"));

        assertNotNull(captured.get());
        assertEquals(List.of("abcde", "123"), captured.get());
        service.shutdown();
    }

    @Test
    void emptyDocumentsDoNotCallApi() {
        AtomicBoolean called = new AtomicBoolean(false);
        RerankService service = service("test-key", 500, 2000, (query, documents) -> {
            called.set(true);
            return List.of();
        });

        RerankService.RerankOutcome outcome = service.rerank("query", List.of());

        assertFalse(outcome.applied());
        assertEquals(RerankService.FALLBACK_NO_DOCUMENTS, outcome.fallbackReason());
        assertFalse(called.get());
        service.shutdown();
    }

    private RerankService service(String apiKey, int timeoutMs, int maxDocChars,
                                  RerankService.RerankClient client) {
        DashScopeConfig config = new DashScopeConfig();
        DashScopeConfig.Rerank rerank = new DashScopeConfig.Rerank();
        rerank.setApiKey(apiKey);
        rerank.setModel("gte-rerank-v2");
        config.setRerank(rerank);
        config.getEmbedding().setApiKey(null);
        return new RerankService(config, timeoutMs, maxDocChars, 2, 8, client);
    }
}
