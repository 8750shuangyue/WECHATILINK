package com.example.demo.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EvalReportWriter {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final ObjectMapper objectMapper;

    public EvalReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReportPaths write(
            EvalConfig config,
            String runId,
            Instant startedAt,
            List<EvalResult> results,
            boolean stoppedByBudget
    ) throws IOException {
        return write(config, runId, startedAt, results, stoppedByBudget, null);
    }

    ReportPaths write(
            EvalConfig config,
            String runId,
            Instant startedAt,
            List<EvalResult> results,
            boolean stoppedByBudget,
            String suffixOverride
    ) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
        results = List.copyOf(results);

        Files.createDirectories(config.outputDir());
        ZonedDateTime localStartedAt = startedAt.atZone(ZoneId.systemDefault());
        String suffix = suffixOverride == null || suffixOverride.isBlank()
                ? FILE_TIME.format(localStartedAt)
                : suffixOverride;

        Path jsonlPath = config.outputDir().resolve("baseline-" + suffix + ".jsonl");
        Path markdownPath = config.outputDir().resolve("baseline-" + suffix + ".md");
        Path failuresPath = config.outputDir().resolve("failures-" + suffix + ".md");
        String previousBaseline = previousBaseline(config.outputDir(), jsonlPath).orElse("无");

        writeJsonLines(jsonlPath, results);
        Files.writeString(
                markdownPath,
                buildMarkdown(
                        config,
                        runId,
                        startedAt,
                        results,
                        stoppedByBudget,
                        previousBaseline
                ),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        Files.writeString(
                failuresPath,
                buildFailuresMarkdown(config, runId, results, stoppedByBudget),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        return new ReportPaths(jsonlPath, markdownPath, failuresPath);
    }

    private void writeJsonLines(Path path, List<EvalResult> results) throws IOException {
        StringBuilder content = new StringBuilder();
        for (EvalResult result : results) {
            content.append(objectMapper.writeValueAsString(result)).append('\n');
        }
        Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private String buildMarkdown(
            EvalConfig config,
            String runId,
            Instant startedAt,
            List<EvalResult> results,
            boolean stoppedByBudget,
            String previousBaseline
    ) {
        Summary summary = summarize(results, config.factPassThreshold());

        StringBuilder markdown = new StringBuilder();
        markdown.append("# AI 评测基线报告\n\n");
        markdown.append("> 运行编号：").append(runId).append("  \n");
        markdown.append("> 开始时间：")
                .append(DISPLAY_TIME.format(startedAt.atZone(ZoneId.systemDefault())))
                .append("  \n");
        markdown.append("> Git 提交：").append(config.commit()).append("  \n");
        markdown.append("> 模型配置：").append(config.model()).append("  \n");
        markdown.append("> 温度：").append(config.temperature()).append("  \n");
        markdown.append("> 预算状态：")
                .append(stoppedByBudget ? "达到上限后停止，属于部分报告" : "完整执行")
                .append("\n\n");

        markdown.append("## 运行参数\n\n");
        markdown.append("- 被测地址：`").append(config.baseUrl()).append("`\n");
        markdown.append("- 题库：`").append(config.caseFile()).append("`\n");
        markdown.append("- TopK：").append(config.topK()).append('\n');
        markdown.append("- 相似度阈值：").append(config.similarityThreshold()).append('\n');
        markdown.append("- Embedding 模型：").append(config.embeddingModel()).append('\n');
        markdown.append("- 引用指令：").append(config.includeCitations() ? "开启" : "关闭").append('\n');
        markdown.append("- 允许工具：")
                .append(config.allowedTools().isEmpty() ? "无" : String.join(", ", config.allowedTools()))
                .append('\n');
        markdown.append("- 最大用例数：")
                .append(config.maxCases() == 0 ? "不限制" : config.maxCases())
                .append('\n');
        markdown.append("- 最大 Token 预算：").append(config.maxTokens()).append("\n\n");

        markdown.append("## 总体指标\n\n");
        markdown.append("| 指标 | 数值 |\n");
        markdown.append("| --- | ---: |\n");
        markdown.append("| 请求记录数 | ").append(summary.count()).append(" |\n");
        markdown.append("| 质量评分样本数 | ").append(summary.qualityCount()).append(" |\n");
        markdown.append("| 质量通过数 | ").append(summary.passed()).append(" |\n");
        markdown.append("| 质量通过率 | ").append(percent(summary.passRate())).append(" |\n");
        markdown.append("| 基础设施失败数 | ").append(summary.infrastructureErrors()).append(" |\n");
        markdown.append("| 首字延迟均值 | ").append(millis(summary.averageFirstTokenMs())).append(" |\n");
        markdown.append("| 总延迟均值 | ").append(millis(summary.averageLatencyMs())).append(" |\n");
        markdown.append("| 总延迟 P95 | ").append(millis(summary.p95LatencyMs())).append(" |\n");
        markdown.append("| 已知 Token 合计 | ").append(summary.actualTokens()).append(" |\n");
        markdown.append("| 估算 Token 合计 | ").append(summary.estimatedTokens()).append(" |\n");
        markdown.append("| 计费口径 Token | ").append(summary.billableTokens()).append(" |\n");
        markdown.append("| 估算成本 | ").append(cost(summary.estimatedCost())).append(" |\n\n");

        markdown.append("## 自动评分\n\n");
        markdown.append("| 指标 | 数值 | 样本数 |\n");
        markdown.append("| --- | ---: | ---: |\n");
        appendMetric(
                markdown,
                "关键事实覆盖率",
                summary.averageFactScore(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.factScore)
        );
        appendMetric(
                markdown,
                "工具选择准确率",
                summary.averageToolSelectionScore(),
                metricSampleCount(
                        results,
                        result -> result.autoScore == null ? null : result.autoScore.toolSelectionScore
                )
        );
        appendMetric(
                markdown,
                "工具执行成功率",
                summary.averageToolSuccessRate(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.toolSuccessRate)
        );
        appendMetric(
                markdown,
                "拒答边界得分",
                summary.averageRefusalScore(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.refusalScore)
        );
        appendMetric(
                markdown,
                "RAG 来源召回率 (Recall@K)",
                summary.averageSourceRecall(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.sourceRecall)
        );
        appendMetric(
                markdown,
                "RAG 命中率 (Hit@1)",
                summary.averageHitAt1(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.hitAt1)
        );
        appendMetric(
                markdown,
                "RAG 平均倒数排名 (MRR@K)",
                summary.averageMrr(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.mrrAtK)
        );
        appendMetric(
                markdown,
                "RAG 排序质量 (nDCG@K)",
                summary.averageNdcg(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.ndcgAtK)
        );
        markdown.append("| 上下文压缩应用率 | ")
                .append(percent(summary.compressionAppliedRate())).append(" | ")
                .append(metricSampleCount(results, result -> result.autoScore == null
                        ? null : booleanAsDouble(result.autoScore.compressionApplied))).append(" |\n");
        appendMetric(
                markdown,
                "上下文压缩率",
                summary.averageCompressionCharReduction(),
                metricSampleCount(results, result -> result.autoScore == null
                        ? null : result.autoScore.compressionCharReduction)
        );
        appendMetric(
                markdown,
                "保护事实保留率",
                summary.averageProtectedFactPreservation(),
                metricSampleCount(results, result -> result.autoScore == null
                        ? null : result.autoScore.compressionProtectedFactPreservation)
        );
        appendMetric(
                markdown,
                "引用覆盖率",
                summary.averageCitationCoverage(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.citationCoverage)
        );
        appendMetric(
                markdown,
                "引用精确率",
                summary.averageCitationPrecision(),
                metricSampleCount(results, result -> result.autoScore == null ? null : result.autoScore.citationPrecision)
        );
        markdown.append("| 无效引用标记数 | ").append(summary.citationInvalidMarkers()).append(" | - |\n");
        markdown.append("| 禁用结论命中数 | ").append(summary.forbiddenClaimHits()).append(" | ")
                .append(summary.qualityCount()).append(" |\n");
        markdown.append("| 禁用工具命中数 | ").append(summary.forbiddenToolHits()).append(" | ")
                .append(summary.qualityCount()).append(" |\n\n");

        markdown.append("## 分类统计\n\n");
        markdown.append("| 分类 | 请求数 | 通过数 | 通过率 | 平均总延迟 |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: |\n");
        summary.categories().values().forEach(category -> markdown
                .append("| ").append(escapeTable(category.name())).append(" | ")
                .append(category.count()).append(" | ")
                .append(category.passed()).append(" | ")
                .append(percent(category.passRate())).append(" | ")
                .append(millis(category.averageLatencyMs())).append(" |\n"));
        markdown.append('\n');

        markdown.append("## 失败类型\n\n");
        if (summary.failureReasons().isEmpty()) {
            markdown.append("本批次没有失败记录。\n\n");
        } else {
            markdown.append("| 类型 | 数量 |\n");
            markdown.append("| --- | ---: |\n");
            summary.failureReasons().forEach((reason, count) ->
                    markdown.append("| ").append(reason).append(" | ").append(count).append(" |\n"));
            markdown.append('\n');
        }

        markdown.append("## Top 10 失败用例\n\n");
        List<EvalResult> topFailures = failedResults(results).stream().limit(10).toList();
        if (topFailures.isEmpty()) {
            markdown.append("无可列出的失败用例。\n\n");
        } else {
            markdown.append("| 用例 | 轮次 | 重复 | 分类 | 原因 | 事实分 | 总延迟 |\n");
            markdown.append("| --- | ---: | ---: | --- | --- | ---: | ---: |\n");
            for (EvalResult result : topFailures) {
                markdown.append("| ").append(escapeTable(result.caseId)).append(" | ")
                        .append(result.turnIndex).append(" | ")
                        .append(result.repetition).append(" | ")
                        .append(escapeTable(result.category)).append(" | ")
                        .append(escapeTable(failureReason(result, config.factPassThreshold()))).append(" | ")
                        .append(decimal(result.autoScore == null ? null : result.autoScore.factScore)).append(" | ")
                        .append(millis(result.latencyMs)).append(" |\n");
            }
            markdown.append('\n');
        }

        appendRagDiagnostics(markdown, results);

        markdown.append("## 能力缺口\n\n");
        markdown.append("- RAG 来源召回：评测通过 SSE `rag-trace` 事件取 `traceId`，再查受控只读接口 ")
                .append("`/api/internal/rag/retrievals/{traceId}` 获取命中来源；仅当题库 `expectedSourceIds` 非空且检索状态为 ")
                .append("`available` 时计算 `sourceRecall`，`path: tools` 的用例不参与。\n");
        markdown.append("- 排名指标：`Hit@1` / `MRR@K` / `nDCG@K` 基于服务端最终 `rank` 顺序，只看公共知识块并按 ")
                .append("`sourceId` 去重；它们衡量期望来源排得靠不靠前，不判断引用的句子是否真的支持结论。\n");
        markdown.append("- 压缩指标：来自服务端 `stats` 的上下文前后字符数与保护事实计数；只验证可保护的数字/时间/剂量/安全句未被丢弃，")
                .append("普通事实的语义保真仍以质量通过率和人工复核为准。\n");
        markdown.append("- 引用指标：通过 SSE `rag-sources` 获取本次来源列表；覆盖率 = 被回答引用的期望来源数 / 期望来源数，")
                .append("精确率 = 被回答引用的期望来源数 / 被回答引用的来源数。只做来源匹配，无法判断编号是否真的支持该句；")
                .append("无效编号只计数，不改变通过判定。\n");
        markdown.append("- 普通流式聊天：接口不返回 Token 用量，本报告按回答长度给出估算值，不能替代服务端账单。\n");
        markdown.append("- 成本：仅按 `AI_EVAL_PRICE_PER_MILLION_TOKENS` 和计费口径 Token 估算，币种由执行人员自行统一。\n\n");

        markdown.append("## 上一版基线\n\n");
        markdown.append("- 上一份基线文件：").append(previousBaseline).append("\n");
        markdown.append("- 自动趋势对比：第一版预留，当前报告不自动判定提升或回退。\n\n");

        markdown.append("## 建议\n\n");
        markdown.append("1. 先人工复核所有关键用例、基础设施失败和 Top 10 失败。\n");
        markdown.append("2. 对失败样本区分提示词、模型、工具参数、知识库内容和服务异常。\n");
        markdown.append("3. 将固定后的题库、模型参数和本报告一起归档，作为下一轮比较基线。\n");
        return markdown.toString();
    }

    private String buildFailuresMarkdown(
            EvalConfig config,
            String runId,
            List<EvalResult> results,
            boolean stoppedByBudget
    ) {
        List<EvalResult> failures = failedResults(results);
        StringBuilder markdown = new StringBuilder();
        markdown.append("# AI 评测失败与复核清单\n\n");
        markdown.append("- 运行编号：").append(runId).append('\n');
        markdown.append("- Git 提交：").append(config.commit()).append('\n');
        markdown.append("- 预算状态：").append(stoppedByBudget ? "部分报告" : "完整执行").append('\n');
        markdown.append("- 失败或需复核记录：").append(failures.size()).append("\n\n");

        if (failures.isEmpty()) {
            markdown.append("本批次没有自动失败或基础设施错误。\n");
            return markdown.toString();
        }

        int index = 1;
        for (EvalResult result : failures) {
            markdown.append("## ").append(index++).append(". ")
                    .append(result.caseId).append(" / 轮次 ")
                    .append(result.turnIndex).append(" / 重复 ")
                    .append(result.repetition).append("\n\n");
            markdown.append("- 分类：").append(result.category).append('\n');
            markdown.append("- 路径：").append(result.path).append('\n');
            markdown.append("- 问题：").append(result.question).append('\n');
            markdown.append("- 失败原因：")
                    .append(failureReason(result, config.factPassThreshold())).append('\n');
            markdown.append("- HTTP 状态：").append(result.httpStatus).append('\n');
            markdown.append("- 总延迟：").append(millis(result.latencyMs)).append('\n');
            markdown.append("- Trace ID：").append(nullSafe(result.traceId)).append('\n');
            markdown.append("- 错误：").append(nullSafe(result.error)).append('\n');
            if (result.autoScore != null) {
                markdown.append("- 事实分：").append(decimal(result.autoScore.factScore)).append('\n');
                markdown.append("- 禁用结论命中：").append(result.autoScore.forbiddenClaimHit).append('\n');
                markdown.append("- 工具选择分：").append(decimal(result.autoScore.toolSelectionScore)).append('\n');
                markdown.append("- 工具成功率：").append(decimal(result.autoScore.toolSuccessRate)).append('\n');
                markdown.append("- 拒答得分：").append(decimal(result.autoScore.refusalScore)).append('\n');
                markdown.append("- 来源召回：").append(decimal(result.autoScore.sourceRecall)).append('\n');
                markdown.append("- 引用覆盖率：").append(decimal(result.autoScore.citationCoverage)).append('\n');
                markdown.append("- 引用精确率：").append(decimal(result.autoScore.citationPrecision)).append('\n');
                markdown.append("- 引用标记数：")
                        .append(result.autoScore.citationMarkerCount == null
                                ? "-" : result.autoScore.citationMarkerCount)
                        .append("，无效：")
                        .append(result.autoScore.citationInvalidCount == null
                                ? "-" : result.autoScore.citationInvalidCount)
                        .append('\n');
            }
            markdown.append("- 工具调用：").append(result.toolCalls.size()).append('\n');
            markdown.append("\n回答摘录：\n\n```text\n")
                    .append(truncate(result.response, 1_500))
                    .append("\n```\n\n");
        }
        return markdown.toString();
    }

    private Summary summarize(List<EvalResult> results, double factThreshold) {
        List<EvalResult> qualityResults = results.stream()
                .filter(this::hasAutoScore)
                .toList();
        int passed = (int) qualityResults.stream().filter(result -> result.passed).count();
        long actualTokens = results.stream()
                .map(result -> result.totalTokens)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        long estimatedTokens = results.stream()
                .filter(result -> result.totalTokens == null)
                .map(result -> result.estimatedTokens)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        double estimatedCost = results.stream()
                .map(result -> result.estimatedCost)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        List<EvalResult> latencyResults = results.stream()
                .filter(result -> result.latencyMs >= 0)
                .toList();

        Map<String, CategorySummary> categories = new LinkedHashMap<>();
        qualityResults.stream()
                .collect(Collectors.groupingBy(
                        result -> result.category == null || result.category.isBlank()
                                ? "unknown"
                                : result.category,
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .forEach((name, values) -> categories.put(name, summarizeCategory(name, values)));

        Map<String, Integer> failureReasons = new LinkedHashMap<>();
        results.stream()
                .filter(result -> isInfrastructureFailure(result)
                        || (hasAutoScore(result) && !result.passed))
                .map(result -> failureReason(result, factThreshold))
                .forEach(reason -> failureReasons.merge(reason, 1, Integer::sum));

        return new Summary(
                results.size(),
                qualityResults.size(),
                passed,
                average(qualityResults, result -> result.autoScore.factScore),
                average(
                        qualityResults,
                        result -> result.autoScore.toolSelectionScore
                ),
                average(
                        qualityResults,
                        result -> result.autoScore.toolSuccessRate
                ),
                average(
                        qualityResults,
                        result -> result.autoScore.refusalScore
                ),
                average(
                        qualityResults,
                        result -> result.autoScore.sourceRecall
                ),
                average(
                        qualityResults,
                        result -> result.autoScore.citationCoverage
                ),
                average(
                        qualityResults,
                        result -> result.autoScore.citationPrecision
                ),
                qualityResults.stream()
                        .map(result -> result.autoScore.citationInvalidCount)
                        .filter(Objects::nonNull)
                        .mapToLong(Integer::longValue)
                        .sum(),
                qualityResults.stream()
                        .filter(result -> Boolean.TRUE.equals(result.autoScore.forbiddenClaimHit))
                        .count(),
                qualityResults.stream()
                        .filter(result -> Boolean.TRUE.equals(result.autoScore.forbiddenToolHit))
                        .count(),
                results.stream().filter(this::isInfrastructureFailure).count(),
                averageLong(
                        results.stream()
                                .map(result -> result.firstTokenMs)
                                .filter(value -> value >= 0)
                                .toList()
                ),
                averageLong(latencyResults.stream().map(result -> result.latencyMs).toList()),
                percentile95(latencyResults.stream().map(result -> result.latencyMs).toList()),
                actualTokens,
                estimatedTokens,
                actualTokens + estimatedTokens,
                estimatedCost,
                categories,
                failureReasons,
                average(qualityResults, result -> result.autoScore.hitAt1),
                average(qualityResults, result -> result.autoScore.mrrAtK),
                average(qualityResults, result -> result.autoScore.ndcgAtK),
                appliedRate(qualityResults, result -> result.autoScore.compressionApplied),
                average(qualityResults, result -> result.autoScore.compressionCharReduction),
                average(qualityResults, result -> result.autoScore.compressionProtectedFactPreservation)
        );
    }

    private Double appliedRate(
            List<EvalResult> results,
            Function<EvalResult, Boolean> extractor
    ) {
        List<Boolean> values = results.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        long applied = values.stream().filter(Boolean.TRUE::equals).count();
        return (double) applied / values.size();
    }

    private Double booleanAsDouble(Boolean value) {
        return value == null ? null : (value ? 1.0 : 0.0);
    }

    private void appendRagDiagnostics(StringBuilder markdown, List<EvalResult> results) {
        markdown.append("## RAG 诊断\n\n");
        List<EvalResult> withStats = results.stream()
                .filter(result -> result.retrievalStats != null && !result.retrievalStats.isEmpty())
                .toList();
        if (withStats.isEmpty()) {
            markdown.append("本批次没有服务端检索统计（旧版本或未开启 `includeTraceStats`）。\n\n");
            return;
        }

        Map<String, Object> sample = withStats.getFirst().retrievalStats;
        markdown.append("| 服务端配置 | 值 |\n");
        markdown.append("| --- | --- |\n");
        appendStatsRow(markdown, "topK", sample.get("topK"));
        appendStatsRow(markdown, "similarityThreshold", sample.get("similarityThreshold"));
        appendStatsRow(markdown, "keywordFloor", sample.get("keywordFloor"));
        appendStatsRow(markdown, "keywordEnabled", sample.get("keywordEnabled"));
        appendStatsRow(markdown, "memoryTopK", sample.get("memoryTopK"));
        appendStatsRow(markdown, "rerankPoolSize", sample.get("rerankPoolSize"));
        appendStatsRow(markdown, "rerankTimeoutMs", sample.get("rerankTimeoutMs"));
        appendStatsRow(markdown, "compressionMaxCharsPerChunk",
                sample.get("compressionMaxCharsPerChunk"));
        markdown.append('\n');

        long statsCount = withStats.size();
        long rerankApplied = withStats.stream()
                .filter(result -> Boolean.TRUE.equals(
                        result.retrievalStats.get("rerankApplied")))
                .count();
        long compressionApplied = withStats.stream()
                .filter(result -> Boolean.TRUE.equals(
                        result.retrievalStats.get("compressionApplied")))
                .count();
        Map<String, Integer> fallbackReasons = new LinkedHashMap<>();
        List<Long> rerankDurations = new ArrayList<>();
        for (EvalResult result : withStats) {
            Object reason = result.retrievalStats.get("rerankFallbackReason");
            if (reason != null && !reason.toString().isBlank()) {
                fallbackReasons.merge(reason.toString(), 1, Integer::sum);
            }
            Object duration = result.retrievalStats.get("rerankDurationMs");
            if (duration instanceof Number number) {
                rerankDurations.add(number.longValue());
            }
        }

        markdown.append("| 运行期诊断 | 值 |\n");
        markdown.append("| --- | --- |\n");
        markdown.append("| 计入统计记录数 | ").append(statsCount).append(" |\n");
        markdown.append("| 重排应用率 | ")
                .append(percent((double) rerankApplied / statsCount)).append(" |\n");
        markdown.append("| 重排回退次数 | ")
                .append(fallbackReasons.values().stream().mapToInt(Integer::intValue).sum())
                .append(" |\n");
        markdown.append("| 重排回退原因 | ")
                .append(fallbackReasons.isEmpty() ? "-" : fallbackReasons.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(", ")))
                .append(" |\n");
        markdown.append("| 重排耗时均值 / P95 | ")
                .append(millis(averageLong(rerankDurations))).append(" / ")
                .append(millis(percentile95(rerankDurations))).append(" |\n");
        markdown.append("| 压缩应用率 | ")
                .append(percent((double) compressionApplied / statsCount)).append(" |\n\n");
    }

    private void appendStatsRow(StringBuilder markdown, String key, Object value) {
        markdown.append("| ").append(key).append(" | ")
                .append(value == null ? "N/A" : value).append(" |\n");
    }

    private CategorySummary summarizeCategory(String name, List<EvalResult> results) {
        long passed = results.stream().filter(result -> result.passed).count();
        List<Long> latencies = results.stream()
                .map(result -> result.latencyMs)
                .filter(value -> value >= 0)
                .toList();
        return new CategorySummary(
                name,
                results.size(),
                passed,
                averageLong(latencies)
        );
    }

    private Double average(
            List<EvalResult> results,
            Function<EvalResult, Double> extractor
    ) {
        List<Double> values = results.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private Double averageLong(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private Double percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index).doubleValue();
    }

    private List<EvalResult> failedResults(List<EvalResult> results) {
        return results.stream()
                .filter(result -> isInfrastructureFailure(result)
                        || (hasAutoScore(result) && !result.passed))
                .sorted(Comparator
                        .comparing((EvalResult result) -> result.error == null ? 1 : 0)
                        .thenComparing(result -> result.critical ? 0 : 1)
                        .thenComparing(result -> result.autoScore == null || result.autoScore.factScore == null
                                ? 0.0
                                : result.autoScore.factScore)
                        .thenComparing(result -> result.latencyMs, Comparator.reverseOrder()))
                .toList();
    }

    private String failureReason(EvalResult result, double factThreshold) {
        if (isInfrastructureFailure(result)) {
            return "基础设施或接口异常";
        }
        if (result.autoScore == null) {
            return "未评分";
        }
        if (Boolean.TRUE.equals(result.autoScore.forbiddenClaimHit)) {
            return "命中禁用结论";
        }
        if (Boolean.TRUE.equals(result.autoScore.forbiddenToolHit)) {
            return "调用了禁用工具";
        }
        if (result.autoScore.refusalScore != null && result.autoScore.refusalScore < 1.0) {
            return "拒答边界不足";
        }
        if (result.autoScore.toolSelectionScore != null && result.autoScore.toolSelectionScore < 1.0) {
            return "工具选择不匹配";
        }
        if (result.autoScore.toolSuccessRate != null && result.autoScore.toolSuccessRate < 1.0) {
            return "工具执行失败";
        }
        if (result.autoScore.factScore != null && result.autoScore.factScore < factThreshold) {
            return "关键事实覆盖不足";
        }
        return result.passed ? "需人工复核" : "其他质量失败";
    }

    private boolean hasAutoScore(EvalResult result) {
        return result.autoScore != null;
    }

    private boolean isInfrastructureFailure(EvalResult result) {
        return result.error != null || result.httpStatus < 200 || result.httpStatus >= 300;
    }

    private Optional<String> previousBaseline(Path outputDir, Path currentJsonl) {
        try {
            if (!Files.isDirectory(outputDir)) {
                return Optional.empty();
            }
            String currentName = currentJsonl.getFileName().toString();
            try (var files = Files.list(outputDir)) {
                return files
                        .filter(path -> path.getFileName().toString().startsWith("baseline-"))
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .filter(path -> !path.toAbsolutePath().normalize()
                                .equals(currentJsonl.toAbsolutePath().normalize()))
                        .filter(path -> path.getFileName().toString().compareTo(currentName) < 0)
                        .max(Comparator.comparing(path -> path.getFileName().toString()))
                        .map(path -> path.getFileName().toString());
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private long metricSampleCount(List<EvalResult> results, Function<EvalResult, Double> extractor) {
        return results.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .count();
    }

    private void appendMetric(StringBuilder markdown, String name, Double value, long sampleCount) {
        markdown.append("| ").append(name).append(" | ")
                .append(decimal(value)).append(" | ")
                .append(sampleCount)
                .append(" |\n");
    }

    private String percent(Double value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }

    private String decimal(Double value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.4f", value);
    }

    private String millis(Double value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.1f ms", value);
    }

    private String millis(long value) {
        return value < 0 ? "N/A" : value + " ms";
    }

    private String cost(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String escapeTable(String value) {
        return nullSafe(value).replace("|", "\\|").replace("\n", " ");
    }

    public record ReportPaths(Path jsonl, Path markdown, Path failures) {
    }

    private record Summary(
            int count,
            int qualityCount,
            int passed,
            Double averageFactScore,
            Double averageToolSelectionScore,
            Double averageToolSuccessRate,
            Double averageRefusalScore,
            Double averageSourceRecall,
            Double averageCitationCoverage,
            Double averageCitationPrecision,
            long citationInvalidMarkers,
            long forbiddenClaimHits,
            long forbiddenToolHits,
            long infrastructureErrors,
            Double averageFirstTokenMs,
            Double averageLatencyMs,
            Double p95LatencyMs,
            long actualTokens,
            long estimatedTokens,
            long billableTokens,
            double estimatedCost,
            Map<String, CategorySummary> categories,
            Map<String, Integer> failureReasons,
            Double averageHitAt1,
            Double averageMrr,
            Double averageNdcg,
            Double compressionAppliedRate,
            Double averageCompressionCharReduction,
            Double averageProtectedFactPreservation
    ) {
        double passRate() {
            return qualityCount == 0 ? 0.0 : (double) passed / qualityCount;
        }
    }

    private record CategorySummary(
            String name,
            int count,
            long passed,
            Double averageLatencyMs
    ) {
        double passRate() {
            return count == 0 ? 0.0 : (double) passed / count;
        }
    }
}
