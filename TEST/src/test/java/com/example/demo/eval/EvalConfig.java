package com.example.demo.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public record EvalConfig(
        boolean enabled,
        String baseUrl,
        String origin,
        String username,
        String password,
        Path caseFile,
        Path outputDir,
        int maxCases,
        long maxTokens,
        int maxRetries,
        Duration requestTimeout,
        double factPassThreshold,
        List<String> allowedTools,
        String model,
        double temperature,
        int topK,
        double similarityThreshold,
        String embeddingModel,
        String commit,
        double pricePerMillionTokens
) {
    private static final List<String> DEFAULT_ALLOWED_TOOLS = List.of(
            "getWeather",
            "getCurrentTime",
            "webSearch",
            "queryPetCare",
            "queryPlantSafety",
            "queryFoodSafety",
            "searchNearbyService",
            "triageSymptoms"
    );

    public static EvalConfig load() {
        Path projectDir = Path.of(System.getProperty(
                "ai.eval.project.dir",
                System.getProperty("user.dir")
        )).toAbsolutePath().normalize();

        String baseUrl = stripTrailingSlash(value(
                "ai.eval.base-url",
                "AI_EVAL_BASE_URL",
                "http://localhost:8080"
        ));

        return new EvalConfig(
                boolValue("ai.eval.enabled", "AI_EVAL_ENABLED", false),
                baseUrl,
                stripTrailingSlash(value("ai.eval.origin", "AI_EVAL_ORIGIN", baseUrl)),
                value("ai.eval.username", "AI_EVAL_USERNAME", ""),
                value("ai.eval.password", "AI_EVAL_PASSWORD", ""),
                resolvePath(projectDir, value(
                        "ai.eval.case-file",
                        "AI_EVAL_CASE_FILE",
                        "eval/ai-eval-smoke-12.jsonl"
                )),
                resolvePath(projectDir, value(
                        "ai.eval.output-dir",
                        "AI_EVAL_OUTPUT_DIR",
                        "eval/results"
                )),
                intValue("ai.eval.max-cases", "AI_EVAL_MAX_CASES", 0),
                longValue("ai.eval.max-tokens", "AI_EVAL_MAX_TOKENS", 200_000L),
                intValue("ai.eval.max-retries", "AI_EVAL_MAX_RETRIES", 2),
                Duration.ofSeconds(intValue(
                        "ai.eval.request-timeout-seconds",
                        "AI_EVAL_REQUEST_TIMEOUT_SECONDS",
                        150
                )),
                doubleValue("ai.eval.fact-pass-threshold", "AI_EVAL_FACT_PASS_THRESHOLD", 0.75),
                listValue("ai.eval.allowed-tools", "AI_EVAL_ALLOWED_TOOLS", DEFAULT_ALLOWED_TOOLS),
                value("ai.eval.model", "AI_EVAL_MODEL", "deepseek-v4-pro"),
                doubleValue("ai.eval.temperature", "AI_EVAL_TEMPERATURE", 0.3),
                intValue("ai.eval.topk", "AI_EVAL_TOPK", 5),
                doubleValue("ai.eval.similarity-threshold", "AI_EVAL_SIMILARITY_THRESHOLD", 0.5),
                value("ai.eval.embedding-model", "AI_EVAL_EMBEDDING_MODEL", "text-embedding-v2"),
                value("ai.eval.commit", "AI_EVAL_COMMIT", resolveGitCommit(projectDir)),
                doubleValue(
                        "ai.eval.price-per-million-tokens",
                        "AI_EVAL_PRICE_PER_MILLION_TOKENS",
                        0.0
                )
        );
    }

    public EvalConfig withCaseSelection(Path selectedCaseFile, int selectedMaxCases) {
        return new EvalConfig(
                enabled,
                baseUrl,
                origin,
                username,
                password,
                selectedCaseFile,
                outputDir,
                selectedMaxCases,
                maxTokens,
                maxRetries,
                requestTimeout,
                factPassThreshold,
                allowedTools,
                model,
                temperature,
                topK,
                similarityThreshold,
                embeddingModel,
                commit,
                pricePerMillionTokens
        );
    }

    public void validateForRun() {
        if (!enabled) {
            return;
        }
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("AI_EVAL_BASE_URL is required");
        }
        if (origin.isBlank()) {
            throw new IllegalStateException("AI_EVAL_ORIGIN is required");
        }
        if (username.isBlank()) {
            throw new IllegalStateException("AI_EVAL_USERNAME is required");
        }
        if (password.isBlank()) {
            throw new IllegalStateException("AI_EVAL_PASSWORD is required");
        }
        if (!Files.isRegularFile(caseFile)) {
            throw new IllegalStateException("Eval case file does not exist: " + caseFile);
        }
        if (maxCases < 0) {
            throw new IllegalStateException("AI_EVAL_MAX_CASES cannot be negative");
        }
        if (maxTokens < 0) {
            throw new IllegalStateException("AI_EVAL_MAX_TOKENS cannot be negative");
        }
        if (maxRetries < 0 || maxRetries > 2) {
            throw new IllegalStateException("AI_EVAL_MAX_RETRIES must be between 0 and 2");
        }
        if (factPassThreshold < 0 || factPassThreshold > 1) {
            throw new IllegalStateException("AI_EVAL_FACT_PASS_THRESHOLD must be between 0 and 1");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalStateException("AI_EVAL_REQUEST_TIMEOUT_SECONDS must be positive");
        }
    }

    private static String value(String propertyName, String environmentName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue.trim();
        }
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        return defaultValue;
    }

    private static boolean boolValue(String propertyName, String environmentName, boolean defaultValue) {
        return Boolean.parseBoolean(value(propertyName, environmentName, Boolean.toString(defaultValue)));
    }

    private static int intValue(String propertyName, String environmentName, int defaultValue) {
        return Integer.parseInt(value(propertyName, environmentName, Integer.toString(defaultValue)));
    }

    private static long longValue(String propertyName, String environmentName, long defaultValue) {
        return Long.parseLong(value(propertyName, environmentName, Long.toString(defaultValue)));
    }

    private static double doubleValue(String propertyName, String environmentName, double defaultValue) {
        return Double.parseDouble(value(propertyName, environmentName, Double.toString(defaultValue)));
    }

    private static List<String> listValue(String propertyName, String environmentName, List<String> defaultValue) {
        String raw = value(propertyName, environmentName, String.join(",", defaultValue));
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static Path resolvePath(Path projectDir, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : projectDir.resolve(path).normalize();
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String resolveGitCommit(Path projectDir) {
        try {
            Process process = new ProcessBuilder(
                    "git",
                    "-C",
                    projectDir.toString(),
                    "rev-parse",
                    "--short",
                    "HEAD"
            ).redirectErrorStream(true).start();

            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unknown";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && !output.isBlank()
                    ? output.toLowerCase(Locale.ROOT)
                    : "unknown";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }
}
