package com.example.wechat.bot.adapter.wechat;

import com.example.wechat.bot.core.model.dto.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 响应分发器——将 {@link MessageResponse} 列表逐条发送到微信。
 * 负责处理 DEFERRED 类型的异步补发逻辑。
 * <p>
 * 调用方通过返回值判断是否有 DEFERRED 任务，以控制输入态（正在输入…）的启停。
 *
 * @return true 表示有 DEFERRED 任务，调用方应保持输入态直到异步完成后由回调停止
 */
@Component
public class ResponseDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ResponseDispatcher.class);

    private final WeChatClientService clientService;

    public ResponseDispatcher(WeChatClientService clientService) {
        this.clientService = clientService;
    }

    /**
     * 分发所有响应到微信
     *
     * @param userId    目标用户 ID
     * @param responses 响应列表
     * @return 是否有 DEFERRED 类型的响应
     */
    public boolean dispatch(String userId, List<MessageResponse> responses) {
        if (responses == null || responses.isEmpty()) return false;

        boolean hasDeferred = false;
        for (MessageResponse resp : responses) {
            if (resp == null || resp.type() == MessageResponse.ResponseType.NONE) continue;
            if (resp.type() == MessageResponse.ResponseType.DEFERRED) {
                hasDeferred = true;
            }
            sendOne(userId, resp);
        }
        return hasDeferred;
    }

    private void sendOne(String userId, MessageResponse response) {
        switch (response.type()) {
            case TEXT -> {
                String text = response.text();
                if (text != null && !text.isBlank()) {
                    clientService.sendTextWithTyping(userId, text);
                }
            }
            case IMAGE -> {
                clientService.sendImage(userId, response.imageBytes(), response.fileName(), "AI 生成的图片");
            }
            case VOICE -> {
                byte[] audio = response.audioBytes();
                if (audio != null && audio.length > 0) {
                    clientService.sendVoiceFile(userId, audio);
                } else {
                    log.warn("音频数据为空，跳过发送");
                }
            }
            case DEFERRED -> {
                // 先发送中间态文字
                String pendingText = response.text();
                if (pendingText != null && !pendingText.isBlank()) {
                    clientService.sendText(userId, pendingText);
                }
                // 异步等待最终结果，完成后补发
                response.deferredResponse().thenAccept(finalResponse -> {
                    clientService.startTyping(userId);
                    sendOne(userId, finalResponse);
                    clientService.stopTyping(userId);
                });
            }
            default -> {
                // NONE 已在 dispatch 中跳过
            }
        }
    }
}
