package com.example.demo.chat;

import com.alibaba.fastjson2.JSONObject;

/**
 * 一次检索/上下文构建的结构化统计，随审计日志落盘，并通过受控观测接口/SSE 暴露。
 * 只保存数量、耗时、开关与回退原因，不包含 query、正文或用户标识。
 *
 * <p>字段全部可空：旧日志缺字段时保持 null，调用方按 unavailable 处理。
 * 使用链式 setter 便于分阶段填充（检索 → 重排 → 压缩）。
 */
public final class RetrievalTraceStats {

    private Integer admittedCount;
    private Integer returnedCount;
    private Integer topK;
    private Integer memoryTopK;
    private Double similarityThreshold;
    private Double keywordFloor;
    private Boolean keywordEnabled;

    private Boolean rerankEnabled;
    private Boolean rerankApplied;
    private String rerankModel;
    private Integer rerankPoolSize;
    private Integer rerankCandidateCount;
    private Integer rerankTimeoutMs;
    private Long rerankDurationMs;
    private String rerankFallbackReason;

    private Boolean compressionEnabled;
    private Boolean compressionApplied;
    private String compressionMode;
    private Integer compressionChunkCount;
    private Integer compressionCompressedCount;
    private Integer compressionSkippedCount;
    private Integer compressionBeforeChars;
    private Integer compressionAfterChars;
    private Long compressionDurationMs;
    private Integer compressionProtectedFacts;
    private Integer compressionPreservedProtectedFacts;
    private Integer compressionCacheHits;
    private Integer compressionMaxCharsPerChunk;
    private String compressionFallbackReason;

    public static RetrievalTraceStats create() {
        return new RetrievalTraceStats();
    }

    public RetrievalTraceStats admittedCount(Integer value) {
        this.admittedCount = value;
        return this;
    }

    public RetrievalTraceStats returnedCount(Integer value) {
        this.returnedCount = value;
        return this;
    }

    public RetrievalTraceStats topK(Integer value) {
        this.topK = value;
        return this;
    }

    public RetrievalTraceStats memoryTopK(Integer value) {
        this.memoryTopK = value;
        return this;
    }

    public RetrievalTraceStats similarityThreshold(Double value) {
        this.similarityThreshold = value;
        return this;
    }

    public RetrievalTraceStats keywordFloor(Double value) {
        this.keywordFloor = value;
        return this;
    }

    public RetrievalTraceStats keywordEnabled(Boolean value) {
        this.keywordEnabled = value;
        return this;
    }

    public RetrievalTraceStats rerankEnabled(Boolean value) {
        this.rerankEnabled = value;
        return this;
    }

    public RetrievalTraceStats rerankApplied(Boolean value) {
        this.rerankApplied = value;
        return this;
    }

    public RetrievalTraceStats rerankModel(String value) {
        this.rerankModel = value;
        return this;
    }

    public RetrievalTraceStats rerankPoolSize(Integer value) {
        this.rerankPoolSize = value;
        return this;
    }

    public RetrievalTraceStats rerankCandidateCount(Integer value) {
        this.rerankCandidateCount = value;
        return this;
    }

    public RetrievalTraceStats rerankTimeoutMs(Integer value) {
        this.rerankTimeoutMs = value;
        return this;
    }

    public RetrievalTraceStats rerankDurationMs(Long value) {
        this.rerankDurationMs = value;
        return this;
    }

    public RetrievalTraceStats rerankFallbackReason(String value) {
        this.rerankFallbackReason = value;
        return this;
    }

    public RetrievalTraceStats compressionEnabled(Boolean value) {
        this.compressionEnabled = value;
        return this;
    }

    public RetrievalTraceStats compressionApplied(Boolean value) {
        this.compressionApplied = value;
        return this;
    }

    public RetrievalTraceStats compressionMode(String value) {
        this.compressionMode = value;
        return this;
    }

    public RetrievalTraceStats compressionChunkCount(Integer value) {
        this.compressionChunkCount = value;
        return this;
    }

    public RetrievalTraceStats compressionCompressedCount(Integer value) {
        this.compressionCompressedCount = value;
        return this;
    }

    public RetrievalTraceStats compressionSkippedCount(Integer value) {
        this.compressionSkippedCount = value;
        return this;
    }

    public RetrievalTraceStats compressionBeforeChars(Integer value) {
        this.compressionBeforeChars = value;
        return this;
    }

    public RetrievalTraceStats compressionAfterChars(Integer value) {
        this.compressionAfterChars = value;
        return this;
    }

    public RetrievalTraceStats compressionDurationMs(Long value) {
        this.compressionDurationMs = value;
        return this;
    }

    public RetrievalTraceStats compressionProtectedFacts(Integer value) {
        this.compressionProtectedFacts = value;
        return this;
    }

    public RetrievalTraceStats compressionPreservedProtectedFacts(Integer value) {
        this.compressionPreservedProtectedFacts = value;
        return this;
    }

    public RetrievalTraceStats compressionCacheHits(Integer value) {
        this.compressionCacheHits = value;
        return this;
    }

    public RetrievalTraceStats compressionMaxCharsPerChunk(Integer value) {
        this.compressionMaxCharsPerChunk = value;
        return this;
    }

    public RetrievalTraceStats compressionFallbackReason(String value) {
        this.compressionFallbackReason = value;
        return this;
    }

    /**
     * 把另一份统计中的非空字段合并进来（用于检索统计 + 压缩统计）。
     */
    public RetrievalTraceStats merge(RetrievalTraceStats other) {
        if (other == null) {
            return this;
        }
        if (other.admittedCount != null) this.admittedCount = other.admittedCount;
        if (other.returnedCount != null) this.returnedCount = other.returnedCount;
        if (other.topK != null) this.topK = other.topK;
        if (other.memoryTopK != null) this.memoryTopK = other.memoryTopK;
        if (other.similarityThreshold != null) this.similarityThreshold = other.similarityThreshold;
        if (other.keywordFloor != null) this.keywordFloor = other.keywordFloor;
        if (other.keywordEnabled != null) this.keywordEnabled = other.keywordEnabled;
        if (other.rerankEnabled != null) this.rerankEnabled = other.rerankEnabled;
        if (other.rerankApplied != null) this.rerankApplied = other.rerankApplied;
        if (other.rerankModel != null) this.rerankModel = other.rerankModel;
        if (other.rerankPoolSize != null) this.rerankPoolSize = other.rerankPoolSize;
        if (other.rerankCandidateCount != null) this.rerankCandidateCount = other.rerankCandidateCount;
        if (other.rerankTimeoutMs != null) this.rerankTimeoutMs = other.rerankTimeoutMs;
        if (other.rerankDurationMs != null) this.rerankDurationMs = other.rerankDurationMs;
        if (other.rerankFallbackReason != null) this.rerankFallbackReason = other.rerankFallbackReason;
        if (other.compressionEnabled != null) this.compressionEnabled = other.compressionEnabled;
        if (other.compressionApplied != null) this.compressionApplied = other.compressionApplied;
        if (other.compressionMode != null) this.compressionMode = other.compressionMode;
        if (other.compressionChunkCount != null) this.compressionChunkCount = other.compressionChunkCount;
        if (other.compressionCompressedCount != null) this.compressionCompressedCount = other.compressionCompressedCount;
        if (other.compressionSkippedCount != null) this.compressionSkippedCount = other.compressionSkippedCount;
        if (other.compressionBeforeChars != null) this.compressionBeforeChars = other.compressionBeforeChars;
        if (other.compressionAfterChars != null) this.compressionAfterChars = other.compressionAfterChars;
        if (other.compressionDurationMs != null) this.compressionDurationMs = other.compressionDurationMs;
        if (other.compressionProtectedFacts != null) this.compressionProtectedFacts = other.compressionProtectedFacts;
        if (other.compressionPreservedProtectedFacts != null) this.compressionPreservedProtectedFacts = other.compressionPreservedProtectedFacts;
        if (other.compressionCacheHits != null) this.compressionCacheHits = other.compressionCacheHits;
        if (other.compressionMaxCharsPerChunk != null) this.compressionMaxCharsPerChunk = other.compressionMaxCharsPerChunk;
        if (other.compressionFallbackReason != null) this.compressionFallbackReason = other.compressionFallbackReason;
        return this;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        putIfPresent(json, "admittedCount", admittedCount);
        putIfPresent(json, "returnedCount", returnedCount);
        putIfPresent(json, "topK", topK);
        putIfPresent(json, "memoryTopK", memoryTopK);
        putIfPresent(json, "similarityThreshold", similarityThreshold);
        putIfPresent(json, "keywordFloor", keywordFloor);
        putIfPresent(json, "keywordEnabled", keywordEnabled);
        putIfPresent(json, "rerankEnabled", rerankEnabled);
        putIfPresent(json, "rerankApplied", rerankApplied);
        putIfPresent(json, "rerankModel", rerankModel);
        putIfPresent(json, "rerankPoolSize", rerankPoolSize);
        putIfPresent(json, "rerankCandidateCount", rerankCandidateCount);
        putIfPresent(json, "rerankTimeoutMs", rerankTimeoutMs);
        putIfPresent(json, "rerankDurationMs", rerankDurationMs);
        putIfPresent(json, "rerankFallbackReason", rerankFallbackReason);
        putIfPresent(json, "compressionEnabled", compressionEnabled);
        putIfPresent(json, "compressionApplied", compressionApplied);
        putIfPresent(json, "compressionMode", compressionMode);
        putIfPresent(json, "compressionChunkCount", compressionChunkCount);
        putIfPresent(json, "compressionCompressedCount", compressionCompressedCount);
        putIfPresent(json, "compressionSkippedCount", compressionSkippedCount);
        putIfPresent(json, "compressionBeforeChars", compressionBeforeChars);
        putIfPresent(json, "compressionAfterChars", compressionAfterChars);
        putIfPresent(json, "compressionDurationMs", compressionDurationMs);
        putIfPresent(json, "compressionProtectedFacts", compressionProtectedFacts);
        putIfPresent(json, "compressionPreservedProtectedFacts", compressionPreservedProtectedFacts);
        putIfPresent(json, "compressionCacheHits", compressionCacheHits);
        putIfPresent(json, "compressionMaxCharsPerChunk", compressionMaxCharsPerChunk);
        putIfPresent(json, "compressionFallbackReason", compressionFallbackReason);
        return json;
    }

    private static void putIfPresent(JSONObject json, String key, Object value) {
        if (value != null) {
            json.put(key, value);
        }
    }
}
