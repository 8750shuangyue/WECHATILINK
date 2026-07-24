package com.example.demo.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SensitiveWordFilter {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveWordFilter.class);

    private static final String DEFAULT_SAFE_RESPONSE = "抱歉，我无法回答这个问题。";

    private final Set<String> sensitiveWords = new HashSet<>();
    private final Set<Pattern> sensitivePatterns = new HashSet<>();

    public SensitiveWordFilter() {
        initSensitiveWords();
    }

    private void initSensitiveWords() {
        sensitiveWords.addAll(Arrays.asList(
                "色情", "暴力", "赌博", "毒品", "违法", "犯罪",
                "枪支", "弹药", "爆炸", "恐怖", "邪教", "反动",
                "辱骂", "侮辱", "诽谤", "谣言", "诈骗", "传销",
                "黑客", "入侵", "攻击", "漏洞", "破解", "窃取",
                "隐私", "个人信息", "身份证", "银行卡", "密码", "验证码",
                "政治", "领导人", "敏感事件", "敏感话题",
                "脏话", "粗口", "恶意", "挑衅", "威胁", "恐吓"
        ));

        sensitivePatterns.add(Pattern.compile("[\\u4e00-\\u9fa5]{2,}"));
        sensitivePatterns.add(Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"));
        sensitivePatterns.add(Pattern.compile("1[3-9]\\d{9}"));
        sensitivePatterns.add(Pattern.compile("[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]"));
    }

    public boolean containsSensitiveWord(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        String lowerText = text.toLowerCase();

        for (String word : sensitiveWords) {
            if (lowerText.contains(word.toLowerCase())) {
                logger.warn("Sensitive word detected: {}", word);
                return true;
            }
        }

        return false;
    }

    public String filter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        if (containsSensitiveWord(text)) {
            logger.warn("Sensitive content detected, returning safe response");
            return DEFAULT_SAFE_RESPONSE;
        }

        return text;
    }

    public String filterWithReplacement(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;

        for (String word : sensitiveWords) {
            result = result.replace(word, "***");
        }

        return result;
    }

    public String getSafeResponse() {
        return DEFAULT_SAFE_RESPONSE;
    }

    public void addSensitiveWord(String word) {
        sensitiveWords.add(word);
        logger.info("Added sensitive word: {}", word);
    }

    public void removeSensitiveWord(String word) {
        sensitiveWords.remove(word);
        logger.info("Removed sensitive word: {}", word);
    }

    public int getSensitiveWordCount() {
        return sensitiveWords.size();
    }
}