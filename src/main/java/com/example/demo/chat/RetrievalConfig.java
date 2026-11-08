package com.example.demo.chat;

/** 一次检索使用的配置快照；数值来自 VectorStoreService 的 @Value 字段。 */
record RetrievalConfig(int topK, int memoryTopK, double similarityThreshold, double keywordFloor,
                       boolean keywordEnabled, boolean rerankEnabled, int rerankCandidatePoolSize,
                       int rerankMinCandidates, String rerankCanaryUsers) {
}
