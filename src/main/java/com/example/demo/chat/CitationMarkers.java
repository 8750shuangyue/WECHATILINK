package com.example.demo.chat;

import java.util.regex.Pattern;

/**
 * 引用标记工具：数据库正文保留 [n]，但进 prompt、摘要和对话向量前剥离，
 * 避免上一轮的编号被模型误当成本轮来源，也避免污染 embedding。
 */
public final class CitationMarkers {

    private static final Pattern CITATION_MARKER = Pattern.compile("\\[\\d{1,2}\\]");

    private CitationMarkers() {
    }

    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return CITATION_MARKER.matcher(text).replaceAll("");
    }
}
