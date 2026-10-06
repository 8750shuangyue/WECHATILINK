package com.example.demo.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "ai.eval.enabled", matches = "true")
class AiEvalRunnerTest {

    private static final long MAX_CASE_COUNT = 100;
    private static final long MAX_TOTAL_TOKENS = 500_000;

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvalReportWriter writer;
    private EvalHttpClient client;
    private String runId;
    private String model;
    private double temperature;
    private int topK;
    private double similarityThreshold;
    private double costPer1kTokens;
    private String commit;

    @Test
    void runEvalSuite() throws Exception {
        String caseFile = System.getProperty("ai.eval.case-file", "eval/ai-eval-v1.jsonl");
        String outputDir = System.getProperty("ai.eval.output", "eval/results");
        String username = System.getProperty("ai.eval.username", "eval_runner");
        String password = System.getProperty("ai.eval.password", "");

        Path casePath = Paths.get(caseFile);
        if (!Files.exists(casePath)) {
            throw new IllegalStateException("题库文件不存在: " + casePath.toAbsolutePath());
        }

        topK = Integer.getInteger("ai.eval.topK", 5);
        similarityThreshold = Double.parseDouble(System.getProperty("ai.eval.similarity-threshold", "0.5"));
        model = System.getProperty("ai.eval.model", "configured-model");
        temperature = Double.parseDouble(System.getProperty("ai.eval.temperature", "0.3"));
        costPer1kTokens = Double.parseDouble(System.getProperty("ai.eval.cost-per-1k-tokens", "0.002"));
        commit = readGitCommit(Paths.get("").toAbsolutePath());
        runId = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        String baseUrl = "http://localhost:" + port;
        client = new EvalHttpClient(objectMapper, baseUrl, baseUrl, username, password);

        if (!client.login()) {
            System.out.println("[EVAL] 评测账号登录失败，尝试自动注册: " + username);
            if (client.register() && client.login()) {
                System.out.println("[EVAL] 评测账号注册成功并完成登录");
            } else {
                throw new IllegalStateException("评测账号登录失败。请确认已用 -Dai.eval.username / -Dai.eval.password 传入评测账号，且该账号存在。");
            }
        }

        EvalReportWriter.RunMeta meta = new EvalReportWriter.RunMeta();
        meta.setRunId(runId);
        meta.setCommit(commit);
        meta.setModel(model);
        meta.setTemperature(temperature);
        meta.setTopK(topK);
        meta.setSimilarityThreshold(similarityThreshold);
        meta.setCostPer1kTokens(costPer1kTokens);
        meta.setStartedAt(Instant.now());
        writer = new EvalReportWriter(Paths.get(outputDir), meta);
        Files.createDirectories(Paths.get(outputDir));

        List<EvalResult> all = new ArrayList<>();
        long caseCount = 0;
        long tokenBudget = 0;
        try (BufferedReader reader = Files.newBufferedReader(casePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                EvalCase c = objectMapper.readValue(line, EvalCase.class);
                if (c.getId() == null || c.getId().isBlank()) {
                    throw new IllegalStateException("题库第 " + lineNo + " 行缺少 id 字段");
                }
                caseCount++;
                if (caseCount > MAX_CASE_COUNT) {
                    System.out.println("[EVAL] 达到单次最大用例数 " + MAX_CASE_COUNT + "，停止执行并生成部分报告。");
                    break;
                }
                int runs = c.isCritical() ? 3 : 1;
                for (int attempt = 1; attempt <= runs; attempt++) {
                    EvalResult r = runCase(c, attempt);
                    EvalScorer.applyAutoScore(c, r);
                    tokenBudget += r.getTotalTokens();
                    all.add(r);
                    writer.appendResult(r);
                    System.out.println("[EVAL] " + r.getCaseId() + " attempt=" + r.getAttempt()
                            + " fact=" + r.getAutoScore().getFactScore()
                            + " forbidden=" + r.getAutoScore().isForbiddenClaimHit()
                            + " toolSel=" + r.getAutoScore().getToolSelectionScore()
                            + " recall=" + r.getAutoScore().getSourceRecall()
                            + " refusal=" + r.getAutoScore().getRefusalScore()
                            + " lat=" + r.getLatencyMs() + "ms"
                            + " tokens=" + r.getTotalTokens()
                            + (r.getError() == null ? "" : " err=" + r.getError()));
                    if (tokenBudget > MAX_TOTAL_TOKENS) {
                        System.out.println("[EVAL] 达到 Token 预算上限，停止执行并生成部分报告。");
                        break;
                    }
                }
                if (tokenBudget > MAX_TOTAL_TOKENS) {
                    break;
                }
            }
        }

        writer.finishMarkdown(all);
        System.out.println("[EVAL] 完成: " + runId + "，共 " + all.size() + " 条结果。报告位于 " + outputDir);
    }

    private EvalResult runCase(EvalCase c, int attempt) {
        EvalResult r = new EvalResult();
        r.setRunId(runId);
        r.setCaseId(c.getId());
        r.setPath(c.getPath());
        r.setCritical(c.isCritical());
        r.setAttempt(attempt);
        r.setCommit(commit);
        r.setModel(model);
        r.setTemperature(temperature);
        r.setTopK(topK);
        r.setSimilarityThreshold(similarityThreshold);
        r.setStartedAt(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atZone(java.time.ZoneOffset.ofHours(8))));
        try {
            if ("tools".equals(c.getPath())) {
                executeToolsPath(c, r);
            } else {
                executeStreamPath(c, r);
            }
        } catch (Exception ex) {
            r.setError(truncate(ex.getMessage(), 500));
        }
        return r;
    }

    private void executeStreamPath(EvalCase c, EvalResult r) throws Exception {
        List<String> turns = new ArrayList<>();
        turns.add(c.getQuestion());
        turns.addAll(c.getMultiTurn());
        for (int i = 0; i < turns.size(); i++) {
            String turn = turns.get(i);
            EvalHttpClient.ChatResponse resp = sendWithRetry(() -> client.chatStream(turn));
            if (resp == null) {
                r.setHttpStatus(0);
                if (r.getError() == null) {
                    r.setError("stream 请求连续失败（已重试 2 次）");
                }
                return;
            }
            if (i == 0) {
                r.setResponse(resp.text());
                r.setHttpStatus(resp.httpStatus());
                r.setFirstTokenMs(resp.firstTokenMs());
                r.setLatencyMs(resp.latencyMs());
                r.setTotalTokens(resp.totalTokens());
            } else {
                r.getMultiTurnOutput().add(resp.text());
            }
        }
        if (!c.getExpectedSourceIds().isEmpty()) {
            r.setRetrieval(probeRetrieval(c.getQuestion(), topK, similarityThreshold));
        }
    }

    private void executeToolsPath(EvalCase c, EvalResult r) throws Exception {
        EvalHttpClient.ToolsResponse resp = sendWithRetry(() -> client.chatWithTools(c.getQuestion()));
        if (resp == null) {
            r.setHttpStatus(0);
            if (r.getError() == null) {
                r.setError("chat-with-tools 请求连续失败（已重试 2 次）");
            }
            return;
        }
        r.setHttpStatus(resp.getHttpStatus());
        r.setLatencyMs(resp.getLatencyMs());
        r.setTraceId(resp.getTraceId());
        r.setTotalTokens(resp.getTotalTokens());
        r.setToolCalls(resp.getToolCalls());
        if (resp.getAnswer() != null && !resp.getAnswer().isBlank()) {
            r.setResponse(resp.getAnswer());
        } else {
            r.setResponse(truncate(resp.getRawBody(), 2000));
        }
        if (resp.isParseFailed()) {
            r.setError("工具响应解析失败，请检查 /api/ai/chat-with-tools 返回结构");
        }
        if (!c.getExpectedSourceIds().isEmpty()) {
            r.setRetrieval(probeRetrieval(c.getQuestion(), topK, similarityThreshold));
        }
    }

    private <T> T sendWithRetry(CheckedSupplier<T> supplier) {
        int attempts = 0;
        Exception last = null;
        while (attempts <= 2) {
            try {
                return supplier.get();
            } catch (Exception ex) {
                last = ex;
                attempts++;
                try {
                    Thread.sleep(1000L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.out.println("[EVAL] 重试耗尽，最后一次异常: " + (last == null ? "unknown" : last.getMessage()));
        return null;
    }

    private List<EvalResult.RetrievalHit> probeRetrieval(String question, int topK, double threshold) {
        List<EvalResult.RetrievalHit> hits = new ArrayList<>();
        try {
            if (applicationContext.containsBean("vectorStoreService")) {
                Object service = applicationContext.getBean("vectorStoreService");
                Method target = null;
                for (Method m : service.getClass().getMethods()) {
                    if ("searchSimilarWithMetadata".equals(m.getName()) && m.getParameterCount() == 1) {
                        target = m;
                        break;
                    }
                }
                if (target == null) {
                    System.out.println("[EVAL] 警告: 未找到 vectorStoreService.searchSimilarWithMetadata(question)，跳过 RAG 召回记录");
                    return hits;
                }
                Object result = target.invoke(service, question);
                if (result instanceof List<?> list) {
                    for (Object item : list) {
                        EvalResult.RetrievalHit hit = toRetrievalHit(item, topK, threshold);
                        if (hit != null) {
                            hits.add(hit);
                        }
                    }
                } else if (result != null) {
                    EvalResult.RetrievalHit hit = toRetrievalHit(result, topK, threshold);
                    if (hit != null) {
                        hits.add(hit);
                    }
                }
            } else {
                System.out.println("[EVAL] 警告: 当前上下文无 vectorStoreService Bean，跳过 RAG 召回记录");
            }
        } catch (Exception ex) {
            System.out.println("[EVAL] RAG 召回探测异常: " + ex.getMessage());
        }
        return hits;
    }

    private EvalResult.RetrievalHit toRetrievalHit(Object item, int topK, double threshold) {
        try {
            JsonNode n = objectMapper.valueToTree(item);
            EvalResult.RetrievalHit hit = new EvalResult.RetrievalHit();
            hit.setTopK(topK);
            hit.setSimilarityThreshold(threshold);
            String sourceId = firstTextField(n, "sourceId", "source", "source_id", "docId", "id");
            if (sourceId == null) {
                return null;
            }
            hit.setSourceId(sourceId);
            String score = firstTextField(n, "score", "similarity", "distance");
            if (score != null) {
                try {
                    hit.setScore(Double.parseDouble(score));
                } catch (NumberFormatException ignored) {
                    hit.setScore(0.0);
                }
            }
            String snippet = firstTextField(n, "content", "text", "snippet", "chunk");
            hit.setSnippet(truncate(snippet == null ? "" : snippet, 200));
            return hit;
        } catch (Exception ex) {
            return null;
        }
    }

    private String firstTextField(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && (v.isTextual() || v.isNumber())) {
                return v.asText();
            }
        }
        return null;
    }

    private String readGitCommit(Path repoRoot) {
        try {
            Path head = repoRoot.resolve(".git").resolve("HEAD");
            if (!Files.exists(head)) {
                return "no-git";
            }
            String ref = Files.readString(head, StandardCharsets.UTF_8).trim();
            if (ref.startsWith("ref:")) {
                Path refPath = repoRoot.resolve(".git").resolve(ref.substring(5).trim());
                if (Files.exists(refPath)) {
                    return Files.readString(refPath, StandardCharsets.UTF_8).trim();
                }
                return ref.substring(5).trim();
            }
            return ref;
        } catch (IOException ex) {
            return "unknown";
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}