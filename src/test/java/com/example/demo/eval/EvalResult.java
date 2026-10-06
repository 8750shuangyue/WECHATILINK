package com.example.demo.eval;

import java.util.ArrayList;
import java.util.List;

public class EvalResult {

    private String runId;
    private String caseId;
    private String path;
    private boolean critical;
    private String commit;
    private String model;
    private double temperature;
    private int topK;
    private double similarityThreshold;
    private String startedAt;
    private long firstTokenMs;
    private long latencyMs;
    private int httpStatus;
    private String response;
    private int responseLength;
    private String traceId;
    private long totalTokens;
    private int attempt;
    private int retries;
    private List<String> multiTurnOutput = new ArrayList<>();
    private List<ToolCall> toolCalls = new ArrayList<>();
    private List<RetrievalHit> retrieval = new ArrayList<>();
    private AutoScore autoScore = new AutoScore();
    private ManualScore manualScore = new ManualScore();
    private String error;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public boolean isCritical() {
        return critical;
    }

    public void setCritical(boolean critical) {
        this.critical = critical;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
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

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public long getFirstTokenMs() {
        return firstTokenMs;
    }

    public void setFirstTokenMs(long firstTokenMs) {
        this.firstTokenMs = firstTokenMs;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
        this.responseLength = response == null ? 0 : response.length();
    }

    public int getResponseLength() {
        return responseLength;
    }

    public void setResponseLength(int responseLength) {
        this.responseLength = responseLength;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public List<String> getMultiTurnOutput() {
        return multiTurnOutput;
    }

    public void setMultiTurnOutput(List<String> multiTurnOutput) {
        this.multiTurnOutput = multiTurnOutput == null ? new ArrayList<>() : multiTurnOutput;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
    }

    public List<RetrievalHit> getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(List<RetrievalHit> retrieval) {
        this.retrieval = retrieval == null ? new ArrayList<>() : retrieval;
    }

    public AutoScore getAutoScore() {
        return autoScore;
    }

    public void setAutoScore(AutoScore autoScore) {
        this.autoScore = autoScore == null ? new AutoScore() : autoScore;
    }

    public ManualScore getManualScore() {
        return manualScore;
    }

    public void setManualScore(ManualScore manualScore) {
        this.manualScore = manualScore == null ? new ManualScore() : manualScore;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String joinedAnswer() {
        StringBuilder sb = new StringBuilder();
        if (response != null && !response.isBlank()) {
            sb.append(response);
        }
        for (String turn : multiTurnOutput) {
            if (turn != null && !turn.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(turn);
            }
        }
        return sb.toString();
    }

    public double factScore() {
        return autoScore.getFactScore();
    }

    public double toolSelectionScore() {
        return autoScore.getToolSelectionScore();
    }

    public double sourceRecall() {
        return autoScore.getSourceRecall();
    }

    public double refusalScore() {
        return autoScore.getRefusalScore();
    }

    public boolean isCriticalCase() {
        return critical;
    }

    public static double compositeScore(EvalResult r) {
        double score = r.getAutoScore().getFactScore() * 0.5;
        if (r.getToolCalls() != null && !r.getToolCalls().isEmpty()) {
            score += r.getAutoScore().getToolSelectionScore() * 0.3;
        }
        if (r.getRetrieval() != null && !r.getRetrieval().isEmpty()) {
            score += r.getAutoScore().getSourceRecall() * 0.2;
        }
        if (r.getCaseId() != null && r.getCaseId().startsWith("refusal-")) {
            score = r.getAutoScore().getRefusalScore();
        }
        if (r.getAutoScore().isForbiddenClaimHit()) {
            score -= 0.5;
        }
        if (r.getError() != null && !r.getError().isBlank()) {
            score = Math.min(score, 0.2);
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    public static class ToolCall {
        private String name;
        private String arguments;
        private String resultStatus = "UNKNOWN";
        private long durationMs;
        private String error;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }

        public String getResultStatus() {
            return resultStatus;
        }

        public void setResultStatus(String resultStatus) {
            this.resultStatus = resultStatus;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(long durationMs) {
            this.durationMs = durationMs;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }

    public static class RetrievalHit {
        private String sourceId;
        private double score;
        private String snippet;
        private int topK;
        private double similarityThreshold;

        public String getSourceId() {
            return sourceId;
        }

        public void setSourceId(String sourceId) {
            this.sourceId = sourceId;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getSnippet() {
            return snippet;
        }

        public void setSnippet(String snippet) {
            this.snippet = snippet;
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
    }

    public static class AutoScore {
        private double factScore;
        private boolean forbiddenClaimHit;
        private double toolSelectionScore;
        private double sourceRecall;
        private double refusalScore;

        public double getFactScore() {
            return factScore;
        }

        public void setFactScore(double factScore) {
            this.factScore = factScore;
        }

        public boolean isForbiddenClaimHit() {
            return forbiddenClaimHit;
        }

        public void setForbiddenClaimHit(boolean forbiddenClaimHit) {
            this.forbiddenClaimHit = forbiddenClaimHit;
        }

        public double getToolSelectionScore() {
            return toolSelectionScore;
        }

        public void setToolSelectionScore(double toolSelectionScore) {
            this.toolSelectionScore = toolSelectionScore;
        }

        public double getSourceRecall() {
            return sourceRecall;
        }

        public void setSourceRecall(double sourceRecall) {
            this.sourceRecall = sourceRecall;
        }

        public double getRefusalScore() {
            return refusalScore;
        }

        public void setRefusalScore(double refusalScore) {
            this.refusalScore = refusalScore;
        }
    }

    public static class ManualScore {
        private Integer correctness;
        private Integer groundedness;
        private Integer safety;
        private String reviewer;
        private String note;

        public Integer getCorrectness() {
            return correctness;
        }

        public void setCorrectness(Integer correctness) {
            this.correctness = correctness;
        }

        public Integer getGroundedness() {
            return groundedness;
        }

        public void setGroundedness(Integer groundedness) {
            this.groundedness = groundedness;
        }

        public Integer getSafety() {
            return safety;
        }

        public void setSafety(Integer safety) {
            this.safety = safety;
        }

        public String getReviewer() {
            return reviewer;
        }

        public void setReviewer(String reviewer) {
            this.reviewer = reviewer;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}