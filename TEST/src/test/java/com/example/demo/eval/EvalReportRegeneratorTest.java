package com.example.demo.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class EvalReportRegeneratorTest {
    @Test
    void regenerateReportFromRawJsonl() throws Exception {
        String configuredPath = System.getProperty(
                "ai.eval.replay-file",
                System.getenv("AI_EVAL_REPLAY_FILE")
        );
        Assumptions.assumeTrue(
                configuredPath != null && !configuredPath.isBlank(),
                "AI_EVAL_REPLAY_FILE is not set; report regeneration was skipped"
        );

        Path resultFile = Path.of(configuredPath).toAbsolutePath().normalize();
        Assumptions.assumeTrue(
                Files.isRegularFile(resultFile),
                "AI_EVAL_REPLAY_FILE does not exist: " + resultFile
        );

        ObjectMapper objectMapper = new ObjectMapper();
        List<EvalResult> results = loadResults(objectMapper, resultFile);
        Assumptions.assumeFalse(results.isEmpty(), "AI_EVAL_REPLAY_FILE contains no result records");

        EvalResult first = results.getFirst();
        EvalConfig config = EvalConfig.load();
        Path caseFile = replayCaseFile(config);
        Map<String, EvalCase> cases = loadCases(objectMapper, caseFile);
        EvalConfig reportConfig = config.withCaseSelection(caseFile, cases.size());
        rescore(results, cases, reportConfig.factPassThreshold());
        boolean stoppedByBudget = Boolean.parseBoolean(System.getProperty(
                "ai.eval.replay-stopped-by-budget",
                System.getenv().getOrDefault("AI_EVAL_REPLAY_STOPPED_BY_BUDGET", "false")
        ));

        EvalReportWriter.ReportPaths paths = new EvalReportWriter(objectMapper).write(
                reportConfig,
                first.runId,
                OffsetDateTime.parse(first.startedAt).toInstant(),
                results,
                stoppedByBudget
        );

        System.out.printf(
                "AI evaluation report regenerated: records=%d, caseFile=%s%nJSONL=%s%nMarkdown=%s%nFailures=%s%n",
                results.size(),
                caseFile,
                paths.jsonl().toAbsolutePath(),
                paths.markdown().toAbsolutePath(),
                paths.failures().toAbsolutePath()
        );
    }

    private List<EvalResult> loadResults(ObjectMapper objectMapper, Path resultFile) throws Exception {
        List<EvalResult> results = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(resultFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    results.add(objectMapper.readValue(line, EvalResult.class));
                }
            }
        }
        return List.copyOf(results);
    }

    private Path replayCaseFile(EvalConfig config) {
        String configuredPath = System.getProperty(
                "ai.eval.replay-case-file",
                System.getenv("AI_EVAL_REPLAY_CASE_FILE")
        );
        if (configuredPath == null || configuredPath.isBlank()) {
            return config.caseFile();
        }
        return Path.of(configuredPath).toAbsolutePath().normalize();
    }

    private Map<String, EvalCase> loadCases(ObjectMapper objectMapper, Path caseFile) throws Exception {
        Assumptions.assumeTrue(
                Files.isRegularFile(caseFile),
                "Replay case file does not exist: " + caseFile
        );
        Map<String, EvalCase> cases = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(caseFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                EvalCase evalCase = objectMapper.readValue(line, EvalCase.class);
                evalCase.validate();
                if (cases.put(evalCase.id(), evalCase) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate evaluation case id at line " + lineNumber + ": " + evalCase.id()
                    );
                }
            }
        }
        return Map.copyOf(cases);
    }

    private void rescore(
            List<EvalResult> results,
            Map<String, EvalCase> cases,
            double factPassThreshold
    ) {
        EvalScorer scorer = new EvalScorer();
        for (EvalResult result : results) {
            if (result.autoScore == null) {
                continue;
            }
            EvalCase evalCase = cases.get(result.caseId);
            if (evalCase == null) {
                throw new IllegalArgumentException(
                        "Replay result references an unknown case id: " + result.caseId
                );
            }
            EvalScorer.ScoreResult score = scorer.score(
                    evalCase,
                    toHttpResult(result),
                    factPassThreshold
            );
            result.autoScore = score.autoScore();
            result.passed = score.passed();
        }
    }

    private EvalHttpClient.HttpResult toHttpResult(EvalResult result) {
        return new EvalHttpClient.HttpResult(
                result.httpStatus,
                result.response,
                result.firstTokenMs,
                result.latencyMs,
                result.traceId,
                result.totalIterations,
                result.totalTokens,
                result.toolCalls,
                result.error,
                result.retryCount
        );
    }
}
