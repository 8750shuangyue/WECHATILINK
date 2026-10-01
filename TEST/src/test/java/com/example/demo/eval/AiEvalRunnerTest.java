package com.example.demo.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiEvalRunnerTest {
    private static final DateTimeFormatter RUN_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runBlackBoxEvaluation() throws Exception {
        EvalConfig config = EvalConfig.load();
        Assumptions.assumeTrue(
                config.enabled(),
                "AI_EVAL_ENABLED=false; real HTTP evaluation was skipped"
        );
        config.validateForRun();

        List<EvalCase> cases = loadCases(config.caseFile());
        validateCasesForConfig(cases, config);
        if (config.maxCases() > 0) {
            cases = List.copyOf(cases.subList(0, Math.min(config.maxCases(), cases.size())));
        }
        assertFalse(cases.isEmpty(), "No evaluation cases were loaded");

        Instant startedAt = Instant.now();
        String runId = "eval-" + RUN_ID_TIME.format(startedAt.atZone(ZoneId.systemDefault()));
        List<EvalResult> results = new ArrayList<>();

        try (EvalHttpClient client = new EvalHttpClient(config, objectMapper)) {
            client.login();
            client.verifyLogin();

            long consumedTokens = 0;
            boolean stoppedByBudget = false;
            outer:
            for (EvalCase evalCase : cases) {
                int repetitions = evalCase.critical() ? 3 : 1;
                for (int repetition = 1; repetition <= repetitions; repetition++) {
                    List<String> turns = evalCase.turns();
                    for (int turnIndex = 0; turnIndex < turns.size(); turnIndex++) {
                        String question = turns.get(turnIndex);
                        EvalHttpClient.HttpResult httpResult = execute(client, config, evalCase, question);
                        EvalResult result = toResult(
                                config,
                                runId,
                                evalCase,
                                question,
                                turnIndex + 1,
                                repetition,
                                httpResult,
                                turnIndex == turns.size() - 1
                        );
                        results.add(result);
                        consumedTokens += billableTokens(result);

                        if (config.maxTokens() > 0 && consumedTokens >= config.maxTokens()) {
                            stoppedByBudget = true;
                            break outer;
                        }
                    }
                }
            }

            EvalReportWriter.ReportPaths reportPaths = new EvalReportWriter(objectMapper).write(
                    config,
                    runId,
                    startedAt,
                    results,
                    stoppedByBudget
            );

            long qualitySamples = results.stream()
                    .filter(result -> result.autoScore != null)
                    .count();
            System.out.printf(
                    "AI evaluation completed: records=%d, qualitySamples=%d, passed=%d, stoppedByBudget=%s%n"
                            + "JSONL=%s%nMarkdown=%s%nFailures=%s%n",
                    results.size(),
                    qualitySamples,
                    results.stream()
                            .filter(result -> result.autoScore != null && result.passed)
                            .count(),
                    stoppedByBudget,
                    reportPaths.jsonl().toAbsolutePath(),
                    reportPaths.markdown().toAbsolutePath(),
                    reportPaths.failures().toAbsolutePath()
            );

            long infrastructureFailures = results.stream()
                    .filter(this::isInfrastructureFailure)
                    .count();
            assertEquals(
                    0L,
                    infrastructureFailures,
                    "The evaluation completed, but infrastructure or API-level failures occurred. "
                            + "Inspect the generated failures report."
            );
        }
    }

    private EvalHttpClient.HttpResult execute(
            EvalHttpClient client,
            EvalConfig config,
            EvalCase evalCase,
            String question
    ) {
        try {
            if ("tools".equals(evalCase.path())) {
                return client.chatWithTools(question, resolveAllowedTools(evalCase, config));
            }
            return client.chatStream(question);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failedResult(exception, config.maxRetries());
        } catch (IOException exception) {
            return failedResult(exception, config.maxRetries());
        }
    }

    private EvalResult toResult(
            EvalConfig config,
            String runId,
            EvalCase evalCase,
            String question,
            int turnIndex,
            int repetition,
            EvalHttpClient.HttpResult httpResult,
            boolean scoreFinalTurn
    ) {
        EvalResult result = new EvalResult();
        result.runId = runId;
        result.caseId = evalCase.id();
        result.caseVersion = evalCase.version();
        result.turnIndex = turnIndex;
        result.repetition = repetition;
        result.path = evalCase.path();
        result.category = evalCase.category();
        result.commit = config.commit();
        result.model = config.model();
        result.temperature = config.temperature();
        result.topK = config.topK();
        result.similarityThreshold = config.similarityThreshold();
        result.startedAt = OffsetDateTime.now(ZoneId.systemDefault()).toString();
        result.question = question;
        result.firstTokenMs = httpResult.firstTokenMs();
        result.latencyMs = httpResult.latencyMs();
        result.httpStatus = httpResult.status();
        result.response = httpResult.response();
        result.responseLength = httpResult.response() == null ? 0 : httpResult.response().length();
        result.traceId = httpResult.traceId();
        result.totalIterations = httpResult.totalIterations();
        result.totalTokens = httpResult.totalTokens();
        result.estimatedTokens = estimateTokens(httpResult.response());
        result.estimatedCost = estimateCost(
                result.totalTokens == null ? result.estimatedTokens : result.totalTokens,
                config.pricePerMillionTokens()
        );
        result.toolCalls = httpResult.toolCalls();
        result.retrieval = List.of();
        result.retrievalStatus = evalCase.expectedSourceIds().isEmpty()
                ? "not_requested_external_black_box"
                : "unavailable_external_black_box";
        result.retryCount = httpResult.retryCount();
        result.critical = evalCase.critical();
        result.error = httpResult.error();

        boolean transportOk = !isInfrastructureFailure(result);
        if (scoreFinalTurn) {
            EvalScorer.ScoreResult score = new EvalScorer().score(
                    evalCase,
                    httpResult,
                    config.factPassThreshold()
            );
            result.autoScore = score.autoScore();
            result.passed = score.passed();
        } else {
            result.passed = transportOk;
        }
        return result;
    }

    private EvalHttpClient.HttpResult failedResult(Exception exception, int retryCount) {
        String message = exception.getMessage();
        String safeMessage = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return new EvalHttpClient.HttpResult(
                0,
                "",
                -1,
                -1,
                null,
                null,
                null,
                List.of(),
                safeMessage,
                retryCount
        );
    }

    private boolean isInfrastructureFailure(EvalResult result) {
        return result.error != null || result.httpStatus < 200 || result.httpStatus >= 300;
    }

    private List<String> resolveAllowedTools(EvalCase evalCase, EvalConfig config) {
        LinkedHashSet<String> configured = new LinkedHashSet<>(config.allowedTools());
        List<String> requested = evalCase.allowedTools().isEmpty()
                ? config.allowedTools()
                : evalCase.allowedTools();
        return requested.stream()
                .filter(configured::contains)
                .distinct()
                .toList();
    }

    private List<EvalCase> loadCases(Path caseFile) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(caseFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                EvalCase evalCase;
                try {
                    evalCase = objectMapper.readValue(line, EvalCase.class);
                    evalCase.validate();
                } catch (Exception exception) {
                    throw new IllegalArgumentException(
                            "Invalid evaluation case at line " + lineNumber + ": " + exception.getMessage(),
                            exception
                    );
                }
                if (!ids.add(evalCase.id())) {
                    throw new IllegalArgumentException("Duplicate evaluation case id: " + evalCase.id());
                }
                cases.add(evalCase);
            }
        }
        return List.copyOf(cases);
    }

    private void validateCasesForConfig(List<EvalCase> cases, EvalConfig config) {
        Set<String> configuredTools = Set.copyOf(config.allowedTools());
        for (EvalCase evalCase : cases) {
            for (String tool : evalCase.allowedTools()) {
                if (!configuredTools.contains(tool)) {
                    throw new IllegalArgumentException(
                            "Case " + evalCase.id() + " requests tool outside AI_EVAL_ALLOWED_TOOLS: " + tool
                    );
                }
            }
            for (String tool : evalCase.expectedTools()) {
                if (!configuredTools.contains(tool)) {
                    throw new IllegalArgumentException(
                            "Case " + evalCase.id() + " expects tool outside AI_EVAL_ALLOWED_TOOLS: " + tool
                    );
                }
            }
        }
    }

    private long billableTokens(EvalResult result) {
        if (result.totalTokens != null) {
            return result.totalTokens;
        }
        return result.estimatedTokens == null ? 0L : result.estimatedTokens;
    }

    private long estimateTokens(String response) {
        if (response == null || response.isEmpty()) {
            return 0L;
        }
        return Math.max(1L, response.length());
    }

    private double estimateCost(Long tokens, double pricePerMillionTokens) {
        if (tokens == null || tokens <= 0 || pricePerMillionTokens <= 0) {
            return 0.0;
        }
        return tokens * pricePerMillionTokens / 1_000_000.0;
    }
}
