package com.example.demo.chat;

import com.example.demo.chat.VectorStoreService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性抽取式上下文压缩：
 * <ul>
 *   <li>只压缩公共知识块，对话记忆逐字保留；</li>
 *   <li>首句、含数字/时间/剂量/单位的句子、含安全提示词的句子强制保留；</li>
 *   <li>预算内按 query 词面重叠挑选可选句，输出必须是原文句子的精确子序列；</li>
 *   <li>任何异常、预算超限或不划算的情况都回退原文，绝不抛到回答链路。</li>
 * </ul>
 */
@Service
public class ContextCompressionService {

    private static final Logger logger = LoggerFactory.getLogger(ContextCompressionService.class);

    public static final String MODE_EXTRACTIVE = "extractive";
    private static final String VERSION = "extractive_v1";

    private static final Pattern PROTECTED_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?"
                    + "|每次|每日|每公斤|每小时|每天|每周|每月|每年|今天|明天|昨天"
                    + "|上午|下午|中午|晚上|早晨|小时|分钟|毫升|毫克|千克|厘米"
                    + "|℃|°C|%");
    private static final List<String> SAFETY_KEYWORDS = List.of(
            "不要", "禁止", "严禁", "切勿", "立即", "马上", "尽快",
            "就医", "就诊", "兽医", "急诊", "中毒", "有毒", "剧毒",
            "剂量", "稀释", "浓度", "通风", "暴晒", "隔离", "停用", "禁用",
            "过敏", "抽搐", "呼吸困难", "休克", "死亡", "危急", "危险", "症状");

    private final boolean enabled;
    private final String mode;
    private final int maxCharsPerChunk;
    private final int minCharsToCompress;
    private final int cacheSize;
    private final boolean protectSafetySentences;
    private final String canaryUsers;

    private final Map<CacheKey, CompressedChunk> cache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, CompressedChunk> eldest) {
                    return size() > Math.max(1, cacheSize);
                }
            };

    @Autowired
    public ContextCompressionService(
            @Value("${chat.rag.compression.enabled:false}") boolean enabled,
            @Value("${chat.rag.compression.mode:extractive}") String mode,
            @Value("${chat.rag.compression.max-chars-per-chunk:360}") int maxCharsPerChunk,
            @Value("${chat.rag.compression.min-chars-to-compress:420}") int minCharsToCompress,
            @Value("${chat.rag.compression.cache-size:512}") int cacheSize,
            @Value("${chat.rag.compression.protect-safety-sentences:true}")
            boolean protectSafetySentences,
            @Value("${chat.rag.compression.canary-users:}") String canaryUsers) {
        this.enabled = enabled;
        this.mode = mode == null || mode.isBlank() ? MODE_EXTRACTIVE : mode;
        this.maxCharsPerChunk = Math.max(1, maxCharsPerChunk);
        this.minCharsToCompress = Math.max(1, minCharsToCompress);
        this.cacheSize = Math.max(1, cacheSize);
        this.protectSafetySentences = protectSafetySentences;
        this.canaryUsers = canaryUsers;
    }

    public CompressionResult compress(String userId, String query, List<SearchResult> results) {
        long startedAt = System.nanoTime();
        RetrievalTraceStats stats = RetrievalTraceStats.create()
                .compressionEnabled(enabled)
                .compressionMode(mode)
                .compressionMaxCharsPerChunk(maxCharsPerChunk);

        if (results == null || results.isEmpty()) {
            stats.compressionApplied(false).compressionDurationMs(elapsed(startedAt));
            return new CompressionResult(results == null ? List.of() : List.copyOf(results), stats);
        }
        if (!enabled) {
            stats.compressionApplied(false).compressionDurationMs(elapsed(startedAt));
            return new CompressionResult(List.copyOf(results), stats);
        }
        if (!FeatureRollout.includes(userId, canaryUsers)) {
            stats.compressionApplied(false)
                    .compressionFallbackReason("canary_excluded")
                    .compressionDurationMs(elapsed(startedAt));
            return new CompressionResult(List.copyOf(results), stats);
        }
        if (!MODE_EXTRACTIVE.equalsIgnoreCase(mode)) {
            stats.compressionApplied(false)
                    .compressionFallbackReason("unsupported_mode")
                    .compressionDurationMs(elapsed(startedAt));
            return new CompressionResult(List.copyOf(results), stats);
        }

        try {
            List<SearchResult> output = new ArrayList<>(results.size());
            Set<String> queryTokens = RetrievalScorer.tokens(query == null ? "" : query);
            int chunkCount = 0;
            int compressedCount = 0;
            int skippedCount = 0;
            int beforeChars = 0;
            int afterChars = 0;
            int protectedFacts = 0;
            int preservedFacts = 0;
            int cacheHits = 0;

            for (SearchResult result : results) {
                if (result == null) {
                    continue;
                }
                String content = result.getContent();
                boolean publicChunk = VectorStoreService.ORIGIN_PUBLIC_KB.equals(result.getOrigin())
                        && content != null && !content.isBlank();
                if (!publicChunk) {
                    output.add(result);
                    continue;
                }
                chunkCount++;
                beforeChars += content.length();
                if (content.length() < minCharsToCompress) {
                    output.add(result);
                    afterChars += content.length();
                    continue;
                }

                CacheKey cacheKey = new CacheKey(
                        content, query, maxCharsPerChunk, protectSafetySentences, VERSION);
                CompressedChunk chunk;
                synchronized (cache) {
                    chunk = cache.get(cacheKey);
                }
                if (chunk == null) {
                    chunk = compressChunk(content, queryTokens);
                    synchronized (cache) {
                        cache.put(cacheKey, chunk);
                    }
                } else {
                    cacheHits++;
                }

                protectedFacts += chunk.protectedFacts();
                preservedFacts += chunk.preservedFacts();
                if (chunk.compressed()) {
                    compressedCount++;
                    afterChars += chunk.content().length();
                    output.add(result.withContent(chunk.content()));
                } else {
                    skippedCount++;
                    afterChars += content.length();
                    output.add(result);
                }
            }

            stats.compressionChunkCount(chunkCount)
                    .compressionCompressedCount(compressedCount)
                    .compressionSkippedCount(skippedCount)
                    .compressionBeforeChars(beforeChars)
                    .compressionAfterChars(afterChars)
                    .compressionProtectedFacts(protectedFacts)
                    .compressionPreservedProtectedFacts(preservedFacts)
                    .compressionCacheHits(cacheHits)
                    .compressionApplied(compressedCount > 0);
            if (compressedCount == 0) {
                stats.compressionFallbackReason("no_eligible_chunk");
            }
            stats.compressionDurationMs(elapsed(startedAt));
            return new CompressionResult(List.copyOf(output), stats);
        } catch (Exception e) {
            logger.warn("[RAG] Context compression failed: {}", e.getMessage());
            stats.compressionApplied(false)
                    .compressionFallbackReason("error")
                    .compressionDurationMs(elapsed(startedAt));
            return new CompressionResult(List.copyOf(results), stats);
        }
    }

    private CompressedChunk compressChunk(String content, Set<String> queryTokens) {
        List<String> sentences = splitSentences(content);
        if (sentences.size() <= 1) {
            int facts = countProtectedFacts(content);
            return new CompressedChunk(content, facts, facts, false);
        }

        boolean[] mandatory = new boolean[sentences.size()];
        int[] sentenceFacts = new int[sentences.size()];
        int mandatoryLength = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            int facts = countProtectedFacts(sentence);
            boolean safety = protectSafetySentences && containsSafetyKeyword(sentence);
            sentenceFacts[i] = facts + (safety ? 1 : 0);
            if (i == 0 || facts > 0 || safety) {
                mandatory[i] = true;
                mandatoryLength += sentence.length();
            }
        }

        int totalFacts = 0;
        for (int facts : sentenceFacts) {
            totalFacts += facts;
        }
        if (mandatoryLength > maxCharsPerChunk) {
            return new CompressedChunk(content, totalFacts, totalFacts, false);
        }

        boolean[] selected = mandatory.clone();
        int used = mandatoryLength;
        List<Integer> optional = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            if (!mandatory[i]) {
                optional.add(i);
            }
        }
        optional.sort(Comparator
                .comparingInt((Integer index) -> overlap(sentences.get(index), queryTokens))
                .reversed()
                .thenComparingInt(index -> index));
        for (int index : optional) {
            int length = sentences.get(index).length();
            if (used + length <= maxCharsPerChunk) {
                selected[index] = true;
                used += length;
            }
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (selected[i]) {
                builder.append(sentences.get(i));
            }
        }
        String compressed = builder.toString();
        if (compressed.isBlank() || compressed.length() >= content.length()) {
            return new CompressedChunk(content, totalFacts, totalFacts, false);
        }

        int preserved = 0;
        for (int i = 0; i < sentences.size(); i++) {
            if (mandatory[i] && compressed.contains(sentences.get(i))) {
                preserved += sentenceFacts[i];
            }
        }
        return new CompressedChunk(compressed, totalFacts, preserved, true);
    }

    /**
     * 换行符归到上一句末尾，保证压缩输出仍然保留原文的分段。
     */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n') {
                if (current.length() == 0 && !sentences.isEmpty()) {
                    int last = sentences.size() - 1;
                    sentences.set(last, sentences.get(last) + '\n');
                } else {
                    current.append(character);
                    if (!current.toString().isBlank()) {
                        sentences.add(current.toString());
                    }
                    current.setLength(0);
                }
                continue;
            }
            current.append(character);
            if (character == '。' || character == '！' || character == '？'
                    || character == '!' || character == '?' || character == '；'
                    || character == ';') {
                if (!current.toString().isBlank()) {
                    sentences.add(current.toString());
                }
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    private int countProtectedFacts(String sentence) {
        int count = 0;
        Matcher matcher = PROTECTED_PATTERN.matcher(sentence);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean containsSafetyKeyword(String sentence) {
        for (String keyword : SAFETY_KEYWORDS) {
            if (sentence.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int overlap(String sentence, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String token : RetrievalScorer.tokens(sentence)) {
            if (queryTokens.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record CompressionResult(List<SearchResult> results, RetrievalTraceStats stats) {
        public CompressionResult {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    private record CompressedChunk(String content, int protectedFacts,
                                   int preservedFacts, boolean compressed) {
    }

    private record CacheKey(String content, String query, int maxCharsPerChunk,
                            boolean protectSafetySentences, String version) {
    }
}
