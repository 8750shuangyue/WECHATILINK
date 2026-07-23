package com.example.wechat.bot.core.model.dto;

/**
 * 统一消息上下文——将原始 WeixinMessage 中所有信息提取为结构化数据，
 * 不包含任何业务判断。下游通过三个辅助方法判断消息类型：
 * <ul>
 *   <li>{@link #isPureText()} — 纯文本/语音 ASR，无图片无文件</li>
 *   <li>{@link #isPureNonText()} — 有图片或文件，但没有任何文字</li>
 *   <li>{@link #isMixed()} — 文字 + 图片或文件（或三者皆有）</li>
 * </ul>
 *
 * @param userId      发送方 ID
 * @param text        文字内容（text_item + voice_item ASR），无则 null
 * @param imageBytes  图片字节，无则 null
 * @param fileContent 文件信息，无则 null
 */
public record UnifiedContext(
        String userId,
        String text,
        byte[] imageBytes,
        FileContent fileContent
) {

    public boolean hasImage() {
        return imageBytes != null && imageBytes.length > 0;
    }

    public boolean hasFile() {
        return fileContent != null;
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /** 纯文本 / 语音 ASR 转写，不含图片或文件 */
    public boolean isPureText() {
        return hasText() && !hasImage() && !hasFile();
    }

    /** 只有图片或文件，没有任何文字 */
    public boolean isPureNonText() {
        return !hasText() && (hasImage() || hasFile());
    }

    /** 文字 + 图片或文件（或三者均有） */
    public boolean isMixed() {
        return hasText() && (hasImage() || hasFile());
    }

    /**
     * 纯非文本场景下，返回友好的媒体类型描述
     */
    public String mediaDescription() {
        if (isPureNonText()) {
            return hasImage() ? "一张图片" : "一个文件";
        }
        return "";
    }
}
