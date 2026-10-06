package com.example.demo.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

public class EvalReportWriter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path outputDir;
    private final RunMeta meta;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path jsonlPath;
    private Path mdPath;
    private Path failurePath;

    public EvalReportWriter(Path outputDir, RunMeta meta) throws IOException {
        this.outputDir = outputDir;
        this.meta = meta;
        Files.createDirectories(outputDir);
        String base = "baseline-" + LocalDateTime.now().format(STAMP);
        this.jsonlPath = outputDir.resolve(base + ".jsonl");
        this.mdPath = outputDir.resolve(base + ".md");
        this.failurePath = outputDir.resolve("failures-" + LocalDateTime.now().format(STAMP) + ".md");
    }

    public void appendResult(EvalResult r) throws IOException {
        String line = objectMapper.writeValueAsString(r);
        Files.writeString(jsonlPath, line + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void finishMarkdown(List<EvalResult> results) throws IOException {
        String report = buildReport(results);
        Files.writeString(mdPath, report, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String failure = buildFailureSummary(results);
        Files.writeString(failurePath, failure, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String buildReport(List<EvalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sekai PetPlant AI 评测基线报告\n\n");
        sb.append("## 1. 本次运行信息\n\n");
        sb.append("- 运行批次：").append(meta.getRunId()).append('\n');
        sb.append("- 开始时间：").append(toLocal(meta.getStartedAt())).append('\n');
        sb.append("- 结束时间：").append(toLocal(meta.getFinishedAt())).append('\n');
        sb.append("- 耗时：").append(meta.getFinishedAt() == null ? "-"
                : Duration.between(meta.getStartedAt(), meta.getFinishedAt()).toMinutes() + " 分钟").append('\n');
        sb.append("- Git 提交：").append(meta.getCommit()).append('\n');

        sb.append("\n## 2. 执行环境和版本\n\n");
        sb.append("| 参数 | 值 |\n|---|---|\n");
        sb.append("| Java | ").append(System.getProperty("java.version", "unknown")).append(" |\n");
        sb.append("| 模型 | ").append(meta.getModel()).append(" |\n");
        sb.append("| 温度 (temperature) | ").append(meta.getTemperature()).append(" |\n");
        sb.append("| 召回 K (topK) | ").append(meta.getTopK()).append(" |\n");
        sb.append("| 相似度阈值 | ").append(meta.getSimilarityThreshold()).append(" |\n");
        sb.append("| 输出路径 | ").append(outputDir.toAbsolutePath()).append(" |\n");
        sb.append("| 原始结果 | ").append(jsonlPath.getFileName()).append(" |\n");

        long total = results.size();
        long success = results.stream().filter(r -> r.getError() == null || r.getError().isBlank()).count();
        long failed = total - success;

        sb.append("\n## 3. 测试集概览\n\n");
        Map<String, Long> byCategory = results.stream()
                .collect(Collectors.groupingBy(EvalResult::getCaseId,
                        Collectors.counting()));
        Map<String, Long> byCategoryPrefix = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : byCategory.entrySet()) {
            String prefix = e.getKey().split("-")[0];
            byCategoryPrefix.merge(prefix, e.getValue(), Long::sum);
        }
        sb.append("| 分类 | 结果记录数 |\n|---|---|\n");
        byCategoryPrefix.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append("| **合计** | **").append(total).append("** |\n");

        sb.append("\n## 4. 总体指标\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 用例总数（含重复执行） | ").append(total).append(" |\n");
        sb.append("| 异常数 | ").append(failed).append(" |\n");
        sb.append("| 异常率 | ").append(pct(failed, total)).append(" |\n");
        sb.append("| 关键事实平均覆盖率 (factScore) | ").append(avg(results, EvalResult::factScore)).append(" |\n");
        double criticalForbidden = results.stream()
                .filter(EvalResult::isCriticalCase)
                .filter(r -> r.getAutoScore().isForbiddenClaimHit())
                .count();
        sb.append("| 关键用例禁用结论命中数 | ").append((long) criticalForbidden).append(" |\n");
        sb.append("| 工具选择平均分 | ").append(avg(results, EvalResult::toolSelectionScore)).append(" |\n");
        sb.append("| RAG 召回平均分 | ").append(avg(results, EvalResult::sourceRecall)).append(" |\n");
        sb.append("| 拒答平均分 | ").append(avg(results, EvalResult::refusalScore)).append(" |\n");

        sb.append("\n## 5. 分类指标\n\n");
        sb.append("| 分类 | 记录数 | 平均 fact | 平均工具 | 平均召回 | 平均拒答 |\n|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Long> e : byCategoryPrefix.entrySet()) {
            List<EvalResult> group = results.stream()
                    .filter(r -> r.getCaseId().startsWith(e.getKey() + "-"))
                    .toList();
            sb.append("| ").append(e.getKey())
                    .append(" | ").append(group.size())
                    .append(" | ").append(avg(group, EvalResult::factScore))
                    .append(" | ").append(avg(group, EvalResult::toolSelectionScore))
                    .append(" | ").append(avg(group, EvalResult::sourceRecall))
                    .append(" | ").append(avg(group, EvalResult::refusalScore))
                    .append(" |\n");
        }

        sb.append("\n## 6. 工具调用统计\n\n");
        long toolCalls = results.stream().mapToLong(r -> r.getToolCalls().size()).sum();
        Map<String, Long> toolUsage = new LinkedHashMap<>();
        for (EvalResult r : results) {
            for (EvalResult.ToolCall t : r.getToolCalls()) {
                toolUsage.merge(t.getName(), 1L, Long::sum);
            }
        }
        sb.append("工具调用总数：").append(toolCalls).append('\n');
        sb.append("\n| 工具 | 调用次数 |\n|---|---|\n");
        toolUsage.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n"));

        sb.append("\n## 7. RAG 召回统计\n\n");
        long ragCases = results.stream().filter(r -> !r.getRetrieval().isEmpty()).count();
        sb.append("携带召回记录的用例数：").append(ragCases).append('\n');

        sb.append("\n## 8. 多轮记忆统计\n\n");
        long memoryCases = results.stream().filter(r -> !r.getMultiTurnOutput().isEmpty()).count();
        sb.append("多轮用例数：").append(memoryCases).append('\n');

        sb.append("\n## 9. 延迟和 Token 成本\n\n");
        List<Long> latencies = results.stream().map(EvalResult::getLatencyMs).sorted().toList();
        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = percentile(latencies, 0.95);
        long totalTokens = results.stream().mapToLong(EvalResult::getTotalTokens).sum();
        double cost = totalTokens / 1000.0 * meta.getCostPer1kTokens();
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 平均总延迟 (ms) | ").append(Math.round(avgLatency)).append(" |\n");
        sb.append("| P95 延迟 (ms) | ").append(Math.round(p95)).append(" |\n");
        sb.append("| Token 总消耗 | ").append(totalTokens).append(" |\n");
        sb.append("| 估算成本（元，@").append(meta.getCostPer1kTokens()).append(" 元/1k token） | ").append(String.format("%.4f", cost)).append(" |\n");

        sb.append("\n## 10. Top 10 失败问题\n\n");
        List<EvalResult> worst = new ArrayList<>(results);
        worst.sort(Comparator
                .comparing((EvalResult r) -> EvalResult.compositeScore(r))
                .thenComparing(EvalResult::getLatencyMs));
        int shown = 0;
        sb.append("| 用例 | 综合分 | 异常 | 备注 |\n|---|---|---|---|\n");
        for (EvalResult r : worst) {
            if (shown >= 10) {
                break;
            }
            if (r.getError() == null && r.getAutoScore().getFactScore() >= 1.0
                    && !r.getAutoScore().isForbiddenClaimHit() && !r.isCriticalCase()
                    && !(r.getCaseId().startsWith("rag-") && r.getAutoScore().getSourceRecall() < 1.0)
                    && !(r.getCaseId().startsWith("tool-") && r.getAutoScore().getToolSelectionScore() < 1.0)
                    && !(r.getCaseId().startsWith("refusal-") && r.getAutoScore().getRefusalScore() < 1.0)) {
                continue;
            }
            sb.append("| ").append(r.getCaseId())
                    .append(" | ").append(String.format("%.2f", EvalResult.compositeScore(r)))
                    .append(" | ").append(r.getError() == null ? "-" : r.getError())
                    .append(" | ").append(r.getResponse() == null ? "" : truncate(r.getResponse(), 60))
                    .append(" |\n");
            shown++;
        }
        if (shown == 0) {
            sb.append("本次无失败或低分用例。\n");
        }

        sb.append("\n## 11. 人工复核结论\n\n");
        sb.append("> 待填写：所有 critical 用例、自动失败用例必须人工复核；其余至少抽查 30%。\n> 评分等级：正确性/依据性/安全性/可执行性（0-2 分）。\n");

        sb.append("\n## 12. 与上一版基线对比\n\n");
        sb.append("> 待填写：与最近一次 baseline-*.jsonl 对比各指标变化。尚无历史基线时为“首版基线”。\n");

        sb.append("\n## 13. 风险和建议\n\n");
        sb.append("> 基于 Top 10 失败问题，区分回答错误 / 检索失败 / 工具失败 / 链路缺失，给出优先修复建议。\n");

        sb.append("\n## 14. 附件和原始结果路径\n\n");
        sb.append("- 原始结果：").append(jsonlPath.toAbsolutePath()).append('\n');
        sb.append("- 失败清单：").append(failurePath.toAbsolutePath()).append('\n');
        return sb.toString();
    }

    private String buildFailureSummary(List<EvalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 失败问题清单\n\n");
        sb.append("运行批次：").append(meta.getRunId()).append("，Git 提交：").append(meta.getCommit()).append("\n\n");
        sb.append("| 用例 | attempt | 异常 | fact | 禁用结论 | 工具分 | 召回分 | 拒答分 | 回答摘要 |\n|---|---|---|---|---|---|---|---|---|\n");
        for (EvalResult r : results) {
            boolean failed = r.getError() != null && !r.getError().isBlank();
            boolean bad = EvalResult.compositeScore(r) < 0.7
                    || (r.getCaseId().startsWith("refusal-") && r.getAutoScore().getRefusalScore() < 1.0)
                     || (r.isCriticalCase() && (r.getAutoScore().isForbiddenClaimHit()
                            || r.getAutoScore().getFactScore() < 0.5));
            if (!failed && !bad) {
                continue;
            }
            sb.append("| ").append(r.getCaseId())
                    .append(" | ").append(r.getAttempt())
                    .append(" | ").append(r.getError() == null ? "-" : r.getError())
                    .append(" | ").append(String.format("%.2f", r.getAutoScore().getFactScore()))
                    .append(" | ").append(r.getAutoScore().isForbiddenClaimHit() ? "命中" : "无")
                    .append(" | ").append(String.format("%.2f", r.getAutoScore().getToolSelectionScore()))
                    .append(" | ").append(String.format("%.2f", r.getAutoScore().getSourceRecall()))
                    .append(" | ").append(String.format("%.2f", r.getAutoScore().getRefusalScore()))
                    .append(" | ").append(r.getResponse() == null ? "" : truncate(r.getResponse(), 80).replace("|", "\\|"))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private double avg(List<EvalResult> results, ScoreGetter getter) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToDouble(getter::get).average().orElse(0.0);
    }

    private double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }

    private String pct(long part, long total) {
        if (total == 0) {
            return "-";
        }
        return String.format("%.2f%%", part * 100.0 / total);
    }

    private String toLocal(Instant instant) {
        if (instant == null) {
            return "-";
        }
        return instant.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        text = text.replace('\n', ' ');
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    @FunctionalInterface
    private interface ScoreGetter {
        double get(EvalResult r);
    }

    public static class RunMeta {
        private String runId;
        private String commit;
        private String model;
        private double temperature;
        private int topK;
        private double similarityThreshold;
        private double costPer1kTokens;
        private Instant startedAt;
        private Instant finishedAt;

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getCommit() {
            return commit;
        }

        public void setCommit(String commit) {
            this.commit = commit;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public double getCostPer1kTokens() {
            return costPer1kTokens;
        }

        public void setCostPer1kTokens(double costPer1kTokens) {
            this.costPer1kTokens = costPer1kTokens;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }

        public Instant getFinishedAt() {
            return finishedAt;
        }

        public void setFinishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
        }
    }
}