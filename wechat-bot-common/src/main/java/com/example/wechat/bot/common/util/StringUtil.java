package com.example.wechat.bot.common.util;

/**
 * 字符串工具类
 */
public class StringUtil {

    private StringUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 从 AI 回复中提取 JSON 部分（去掉 markdown 代码块标记等）。
     * <p>
     * 移除 ```json 或 ``` 标记，提取第一个 {…} 之间的内容。
     * 如果找不到 {…}，返回原文本。
     *
     * @param text AI 回复的原始文本
     * @return 提取后的 JSON 字符串
     */
    public static String extractJsonFromResponse(String text) {
        text = text.replaceAll("```(?:json)?", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
    /**
     * 截断字符串，超出 maxLen 的部分替换为 ...
     *
     * @param s      原始字符串
     * @param maxLen 最大长度
     * @return 截断后的字符串，s 为 null 时返回 null
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 将字节数格式化为人类可读的大小（B/KB/MB）
     *
     * @param size 字节数
     * @return 格式化后的字符串，如 "1.5 MB"
     */
    public static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    /**
     * 净化字符串，移除控制字符和文件名字符中可能引起问题的特殊字符
     *
     * @param s 原始字符串
     * @return 净化后的字符串，s 为 null 时返回空字符串
     */
    public static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\x00-\\x1f<>\"'|\\{\\}]", "");
    }

}