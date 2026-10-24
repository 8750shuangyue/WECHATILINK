package com.example.demo.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Spring AI 响应文本提取。
 *
 * <p>{@link AssistantMessage#toString()} 返回的是调试串（包含 messageType/metadata 等），
 * 不是模型输出正文；纯文本必须通过 {@link AssistantMessage#getText()} 获取。
 */
public final class ChatResponseText {

    private ChatResponseText() {
    }

    public static String extract(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Generation result = response.getResult();
        if (result == null) {
            return null;
        }
        AssistantMessage output = result.getOutput();
        return output != null ? output.getText() : null;
    }

    public static String extract(ChatResponse response, String fallback) {
        String text = extract(response);
        return text != null && !text.isBlank() ? text : fallback;
    }
}
