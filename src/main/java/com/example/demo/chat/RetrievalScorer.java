package com.example.demo.chat;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 关键词召回打分。全部是静态纯函数，不依赖 Spring、网络或数据库，可以直接单测。
 *
 * <p>中文没有天然词边界，这里不引入分词依赖，改用 CJK 二元组（bigram）匹配；
 * 拉丁字母与数字按词切分。打分是 IDF 加权的「查询覆盖率」，取值 [0,1]。
 *
 * <p>这个通道是为了补向量通道的短板：问题的措辞常常是「症状/情境」
 * （叶子发黄、蚜虫、湿尾），而知识块的措辞是「主题」（浇水与积水、叶片卷曲排查），
 * 两者余弦容易压到阈值以下；但「蚜虫」这类词在文档里往往是原词，
 * 词面匹配一打就中。
 */
public final class RetrievalScorer {

    private RetrievalScorer() {
    }

    /**
     * 切分文本为匹配单元：CJK 按二元组滑窗，拉丁/数字按词。
     * 与标定脚本 {@code scripts/rag-calibration.py} 的 bigrams() 保持逐字一致，
     * 由 {@code RagCalibrationGoldenTest} 锁定。
     */
    public static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        int length = lowered.length();
        int index = 0;
        while (index < length) {
            char current = lowered.charAt(index);
            if (isHan(current)) {
                int end = index;
                while (end < length && isHan(lowered.charAt(end))) {
                    end++;
                }
                String segment = lowered.substring(index, end);
                if (segment.length() == 1) {
                    tokens.add(segment);
                }
                for (int i = 0; i + 1 < segment.length(); i++) {
                    tokens.add(segment.substring(i, i + 2));
                }
                index = end;
            } else if (isAsciiAlphanumeric(current)) {
                int end = index;
                while (end < length && isAsciiAlphanumeric(lowered.charAt(end))) {
                    end++;
                }
                String word = lowered.substring(index, end);
                if (word.length() >= 2) {
                    tokens.add(word);
                }
                index = end;
            } else {
                index++;
            }
        }
        return tokens;
    }

    private static boolean isHan(char value) {
        return value >= '一' && value <= '鿿';
    }

    /**
     * 只认 ASCII 字母数字，与其他语言字符一样当作分隔符——这样中文语境下
     * 的英文与数字才不会被粘成一个整体。
     */
    private static boolean isAsciiAlphanumeric(char value) {
        return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
    }

    /**
     * 基于文档频率的 IDF，{@code log((N+1)/(df+1)) + 1}，保证恒为正。
     * 语料范围由调用方决定；公共知识召回只应传入公共知识块。
     */
    public static Map<String, Double> idf(Collection<Set<String>> documents) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        int total = 0;
        for (Set<String> document : documents) {
            if (document == null) {
                continue;
            }
            total++;
            for (String token : document) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>(Math.max(16, documentFrequency.size() * 2));
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            idf.put(entry.getKey(),
                    Math.log((total + 1.0) / (entry.getValue() + 1.0)) + 1.0);
        }
        return idf;
    }

    /**
     * 双通道准入判定：向量通道够线，或者（公共知识且关键词通道开启且词面够线）。
     *
     * <p>抽成纯函数是为了让「检索策略」本身能被真实语料夹具直接验证，
     * 而不是只在服务内部隐式生效。
     *
     * @param publicKnowledge 只有公共知识块参与关键词通道；对话记忆刻意排除，
     *                        否则同一会话的旧轮次会因词面天然重合被大量灌入
     */
    public static boolean admits(double similarity, double keywordScore, boolean publicKnowledge,
                                 boolean keywordEnabled, double vectorFloor, double keywordFloor) {
        if (similarity >= vectorFloor) {
            return true;
        }
        return publicKnowledge && keywordEnabled && keywordScore >= keywordFloor;
    }

    /**
     * IDF 加权的查询覆盖率：查询里有多少「权重」在文档中出现过。
     * 查询为空、文档为空、或 IDF 为空时返回 0。
     */
    public static double keywordScore(Set<String> queryTokens, Set<String> documentTokens,
                                      Map<String, Double> idf) {
        if (queryTokens == null || queryTokens.isEmpty()
                || documentTokens == null || documentTokens.isEmpty()) {
            return 0.0;
        }
        double matched = 0.0;
        double total = 0.0;
        for (String token : queryTokens) {
            double weight = idf == null ? 1.0 : idf.getOrDefault(token, 1.0);
            total += weight;
            if (documentTokens.contains(token)) {
                matched += weight;
            }
        }
        return total <= 0.0 ? 0.0 : matched / total;
    }
}
