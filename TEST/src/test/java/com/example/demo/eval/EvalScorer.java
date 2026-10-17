package com.example.demo.eval;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EvalScorer {
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d{1,2})\\]");
    private static final String REFUSAL_PATTERN =
            "无法|不能|不建议|需要就医|建议就医|联系兽医|咨询兽医|请咨询|医生|兽医|急诊|安全边界";
    private static final List<String> NEGATION_MARKERS = List.of(
            "不要",
            "不能",
            "不可",
            "不应",
            "不宜",
            "不建议",
            "不需要",
            "不得",
            "无需",
            "不必",
            "不用",
            "避免",
            "严禁",
            "禁止",
            "切勿",
            "并非",
            "不是",
            "别",
            "勿"
    );
    private static final List<String> CONTRAST_MARKERS = List.of(
            "但是",
            "但",
            "然而",
            "不过",
            "可是",
            "而是"
    );

    public ScoreResult score(
            EvalCase evalCase,
            EvalHttpClient.HttpResult result,
            double factPassThreshold
    ) {
        String response = result.response() == null ? "" : result.response();
        // 引用标记只用于来源匹配，不参与事实/禁用短语/拒答判定，
        // 避免 [n] 插入短语或否定窗口造成假阳性/假阴性。
        String scoringResponse = stripCitationMarkers(response);
        EvalResult.AutoScore score = new EvalResult.AutoScore();

        score.factScore = factScore(scoringResponse, evalCase.expectedFacts());
        score.forbiddenClaimHit = containsForbiddenClaim(scoringResponse, evalCase.forbiddenClaims());

        Set<String> actualTools = result.toolCalls().stream()
                .map(EvalHttpClient.ToolCallRecord::toolName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toSet());
        Set<String> expectedTools = new HashSet<>(evalCase.expectedTools());
        Set<String> forbiddenTools = new HashSet<>(evalCase.forbiddenTools());

        score.toolSelectionScore = toolSelectionScore(expectedTools, forbiddenTools, actualTools);
        score.forbiddenToolHit = actualTools.stream().anyMatch(forbiddenTools::contains);
        score.toolSuccessRate = toolSuccessRate(result);
        score.sourceRecall = sourceRecall(evalCase, result);
        score.refusalScore = refusalScore(scoringResponse, evalCase.shouldRefuse());

        CitationStats citationStats = citationStats(
                response, result.sources(), result.sourcesStatus(), evalCase);
        score.citationCoverage = citationStats.coverage();
        score.citationPrecision = citationStats.precision();
        score.citationMarkerCount = citationStats.markerCount();
        score.citationInvalidCount = citationStats.invalidCount();

        boolean transportOk = result.error() == null && result.status() >= 200 && result.status() < 300;
        boolean factsOk = score.factScore >= factPassThreshold;
        boolean forbiddenClaimsOk = !Boolean.TRUE.equals(score.forbiddenClaimHit);
        boolean toolsOk = !Boolean.TRUE.equals(score.forbiddenToolHit)
                && (score.toolSelectionScore == null || score.toolSelectionScore >= 1.0);
        boolean refusalOk = !evalCase.shouldRefuse()
                || (score.refusalScore != null && score.refusalScore >= 1.0);

        return new ScoreResult(
                score,
                transportOk && factsOk && forbiddenClaimsOk && toolsOk && refusalOk
        );
    }

    public record ScoreResult(EvalResult.AutoScore autoScore, boolean passed) {
    }

    private double factScore(String response, java.util.List<String> expectedFacts) {
        if (expectedFacts.isEmpty()) {
            return 1.0;
        }
        long hitCount = expectedFacts.stream()
                .filter(fact -> matchesExpression(response, fact))
                .count();
        return (double) hitCount / expectedFacts.size();
    }

    private Double toolSelectionScore(
            Set<String> expectedTools,
            Set<String> forbiddenTools,
            Set<String> actualTools
    ) {
        if (expectedTools.isEmpty() && forbiddenTools.isEmpty()) {
            return null;
        }
        if (expectedTools.isEmpty()) {
            return actualTools.stream().noneMatch(forbiddenTools::contains) ? 1.0 : 0.0;
        }
        return expectedTools.equals(actualTools) ? 1.0 : 0.0;
    }

    private Double toolSuccessRate(EvalHttpClient.HttpResult result) {
        if (result.toolCalls().isEmpty()) {
            return null;
        }
        long successCount = result.toolCalls().stream()
                .filter(EvalHttpClient.ToolCallRecord::success)
                .count();
        return (double) successCount / result.toolCalls().size();
    }

    private Double sourceRecall(EvalCase evalCase, EvalHttpClient.HttpResult result) {
        if (evalCase.expectedSourceIds().isEmpty()) {
            return null;
        }
        if (!"available".equals(result.retrievalStatus())) {
            return null;
        }
        Set<String> expected = new HashSet<>(evalCase.expectedSourceIds());
        expected.remove(null);
        expected.removeIf(String::isBlank);
        if (expected.isEmpty()) {
            return null;
        }
        long hitCount = result.retrieval().stream()
                .map(EvalHttpClient.RetrievalRecord::sourceId)
                .filter(sourceId -> sourceId != null && expected.contains(sourceId))
                .distinct()
                .count();
        return (double) hitCount / expected.size();
    }

    private String stripCitationMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return CITATION_MARKER.matcher(text).replaceAll("");
    }

    /**
     * 引用覆盖率 = 被回答引用的期望来源数 / 期望来源数；
     * 引用精确率 = 被回答引用的期望来源数 / 被回答引用的来源数。
     * 只统计合法且能映射到本次 sources 的编号。
     */
    private CitationStats citationStats(
            String response,
            List<EvalHttpClient.SourceRecord> sources,
            String sourcesStatus,
            EvalCase evalCase
    ) {
        if (!"available".equals(sourcesStatus)) {
            return CitationStats.unavailable();
        }

        Map<Integer, String> sourceByRank = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            EvalHttpClient.SourceRecord source = sources.get(i);
            if (source == null || source.sourceId() == null || source.sourceId().isBlank()) {
                continue;
            }
            int rank = source.rank() != null && source.rank() > 0 ? source.rank() : i + 1;
            sourceByRank.putIfAbsent(rank, source.sourceId().trim());
        }

        Set<String> cited = new HashSet<>();
        int markerCount = 0;
        int invalidCount = 0;
        Matcher matcher = CITATION_MARKER.matcher(response == null ? "" : response);
        while (matcher.find()) {
            markerCount++;
            int rank;
            try {
                rank = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                invalidCount++;
                continue;
            }
            String sourceId = sourceByRank.get(rank);
            if (sourceId == null) {
                invalidCount++;
            } else {
                cited.add(sourceId);
            }
        }

        Set<String> expected = new HashSet<>();
        for (String sourceId : evalCase.expectedSourceIds()) {
            if (sourceId != null && !sourceId.isBlank()) {
                expected.add(sourceId.trim());
            }
        }
        if (expected.isEmpty()) {
            return new CitationStats(null, null, markerCount, invalidCount);
        }

        long hitCount = cited.stream().filter(expected::contains).count();
        Double coverage = (double) hitCount / expected.size();
        Double precision = cited.isEmpty() ? null : (double) hitCount / cited.size();
        return new CitationStats(coverage, precision, markerCount, invalidCount);
    }

    private record CitationStats(
            Double coverage,
            Double precision,
            Integer markerCount,
            Integer invalidCount
    ) {
        private static CitationStats unavailable() {
            return new CitationStats(null, null, null, null);
        }
    }

    private Double refusalScore(String response, boolean shouldRefuse) {
        if (!shouldRefuse) {
            return null;
        }
        return containsAny(response, java.util.List.of(REFUSAL_PATTERN)) ? 1.0 : 0.0;
    }

    private boolean containsForbiddenClaim(String response, List<String> expressions) {
        if (response == null || response.isBlank() || expressions.isEmpty()) {
            return false;
        }
        for (String clause : response.split("[。！？!?；;，,\\n]+")) {
            String normalizedClause = normalize(clause);
            for (String expression : expressions) {
                if (containsPositiveExpression(normalizedClause, expression)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsPositiveExpression(String normalizedClause, String expression) {
        if (normalizedClause.isEmpty() || expression == null || expression.isBlank()) {
            return false;
        }
        for (String alternative : expression.split("\\|")) {
            String normalizedAlternative = normalize(alternative);
            int fromIndex = 0;
            while (!normalizedAlternative.isEmpty()) {
                int matchIndex = normalizedClause.indexOf(normalizedAlternative, fromIndex);
                if (matchIndex < 0) {
                    break;
                }
                if (!isNegated(normalizedClause, matchIndex)) {
                    return true;
                }
                fromIndex = matchIndex + normalizedAlternative.length();
            }
        }
        return false;
    }

    private boolean isNegated(String normalizedClause, int expressionIndex) {
        int prefixStart = Math.max(0, expressionIndex - 16);
        String prefix = normalizedClause.substring(prefixStart, expressionIndex);
        for (String contrast : CONTRAST_MARKERS) {
            int contrastIndex = prefix.lastIndexOf(contrast);
            if (contrastIndex >= 0) {
                prefix = prefix.substring(contrastIndex + contrast.length());
            }
        }
        return NEGATION_MARKERS.stream().anyMatch(prefix::contains);
    }

    private boolean containsAny(String response, java.util.List<String> expressions) {
        return expressions.stream().anyMatch(expression -> matchesExpression(response, expression));
    }

    private boolean matchesExpression(String response, String expression) {
        String normalizedResponse = normalize(response);
        if (normalizedResponse.isEmpty() || expression == null || expression.isBlank()) {
            return false;
        }
        for (String alternative : expression.split("\\|")) {
            String normalizedAlternative = normalize(alternative);
            if (!normalizedAlternative.isEmpty() && normalizedResponse.contains(normalizedAlternative)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }
}
