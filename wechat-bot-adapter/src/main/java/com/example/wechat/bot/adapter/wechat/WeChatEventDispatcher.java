package com.example.wechat.bot.adapter.wechat;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class WeChatEventDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(WeChatEventDispatcher.class);

    private final List<Consumer<String>> qrCodeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> loginStatusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<WeixinMessage>> messageListeners = new CopyOnWriteArrayList<>();

    public void addQrCodeListener(Consumer<String> listener) {
        qrCodeListeners.add(listener);
    }

    public void addLoginStatusListener(Consumer<String> listener) {
        loginStatusListeners.add(listener);
    }

    public void addMessageListener(Consumer<WeixinMessage> listener) {
        messageListeners.add(listener);
    }

    public void removeQrCodeListener(Consumer<String> listener) {
        qrCodeListeners.remove(listener);
    }

    public void removeLoginStatusListener(Consumer<String> listener) {
        loginStatusListeners.remove(listener);
    }

    public void removeMessageListener(Consumer<WeixinMessage> listener) {
        messageListeners.remove(listener);
    }

    public void dispatchQrCode(String qrCode) {
        for (Consumer<String> listener : qrCodeListeners) {
            try {
                listener.accept(qrCode);
            } catch (Exception e) {
                logger.error("通知二维码事件失败", e);
            }
        }
    }

    public void dispatchLoginStatus(String status) {
        for (Consumer<String> listener : loginStatusListeners) {
            try {
                listener.accept(status);
            } catch (Exception e) {
                logger.error("通知登录状态事件失败", e);
            }
        }
    }

    public void dispatchMessage(WeixinMessage message) {
        for (Consumer<WeixinMessage> listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                logger.error("通知消息事件失败", e);
            }
        }
    }
}
