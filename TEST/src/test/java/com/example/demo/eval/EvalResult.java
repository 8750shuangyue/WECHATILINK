package com.example.demo.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvalResult {
    public String runId;
    public String caseId;
    public int caseVersion;
    public int turnIndex;
    public int repetition;
    public String path;
    public String category;
    public String commit;
    public String model;
    public double temperature;
    public int topK;
    public double similarityThreshold;
    public String startedAt;
    public String question;
    public long firstTokenMs = -1;
    public long latencyMs;
    public int httpStatus;
    public String response;
    public int responseLength;
    public String traceId;
    public Integer totalIterations;
    public Long totalTokens;
    public Long estimatedTokens;
    public Double estimatedCost;
    public List<EvalHttpClient.ToolCallRecord> toolCalls = List.of();
    public List<RetrievalRecord> retrieval = List.of();
    public String retrievalStatus;
    /** 服务端检索/重排/压缩统计快照；旧记录为 null。 */
    public Map<String, Object> retrievalStats;
    public List<EvalHttpClient.SourceRecord> sources = List.of();
    public String sourcesStatus;
    public AutoScore autoScore;
    public ManualScore manualScore = new ManualScore();
    public int retryCount;
    public boolean critical;
    public boolean passed;
    public String error;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AutoScore {
        public Double factScore;
        public Boolean forbiddenClaimHit;
        public Boolean forbiddenToolHit;
        public Double toolSelectionScore;
        public Double toolSuccessRate;
        public Double sourceRecall;
        public Double refusalScore;
        public Double citationCoverage;
        public Double citationPrecision;
        public Integer citationMarkerCount;
        public Integer citationInvalidCount;
        public Double hitAt1;
        public Double mrrAtK;
        public Double ndcgAtK;
        public Boolean compressionApplied;
        public Double compressionCharReduction;
        public Double compressionProtectedFactPreservation;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ManualScore {
        public Integer correctness;
        public Integer groundedness;
        public Integer safety;
        public Integer actionability;
        public String reviewer;
        public String note;
    }

    public record RetrievalRecord(
            String documentId,
            String sourceId,
            Double similarity,
            String content,
            String origin,
            Integer rank,
            Integer cosineRank,
            Double rerankScore,
            Double keywordScore,
            String channel
    ) {
    }
}
