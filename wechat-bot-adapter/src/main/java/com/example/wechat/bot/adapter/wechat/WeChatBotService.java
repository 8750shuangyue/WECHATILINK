package com.example.wechat.bot.adapter.wechat;

import com.example.wechat.bot.core.handler.MessageRouter;
import com.example.wechat.bot.core.model.dto.MessageResponse;
import com.example.wechat.bot.core.model.dto.UnifiedContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 微信机器人服务——薄适配层。
 * <p>
 * 接收消息后依次执行三个步骤：
 * <ol>
 *   <li>分类（{@link MessageClassifier#classify(WeixinMessage)}）→ {@link UnifiedContext}</li>
 *   <li>路由处理（{@link MessageRouter#route(UnifiedContext)}）→ {@link MessageResponse 列表}</li>
 *   <li>分发响应（{@link ResponseDispatcher#dispatch(String, List)}）</li>
 * </ol>
 * 方法内不再包含任何业务判断或响应类型细节。
 */
@Service
public class WeChatBotService {

    private static final Logger log = LoggerFactory.getLogger(WeChatBotService.class);

    private final WeChatClientService clientService;
    private final WeChatEventDispatcher eventDispatcher;
    private final MessageClassifier classifier;
    private final MessageRouter router;
    private final ResponseDispatcher dispatcher;

    public WeChatBotService(WeChatClientService clientService,
                             WeChatEventDispatcher eventDispatcher,
                             MessageClassifier classifier,
                             MessageRouter router,
                             ResponseDispatcher dispatcher) {
        this.clientService = clientService;
        this.eventDispatcher = eventDispatcher;
        this.classifier = classifier;
        this.router = router;
        this.dispatcher = dispatcher;
    }

    @PostConstruct
    public void init() {
        eventDispatcher.addMessageListener(this::handleMessage);
    }

    public String startLogin() {
        return clientService.startLogin();
    }

    public void sendText(String userId, String text) {
        clientService.sendText(userId, text);
    }

    public void sendImage(String userId, byte[] imageBytes, String fileName, String description) {
        clientService.sendImage(userId, imageBytes, fileName, description);
    }

    public boolean isLoggedIn() {
        return clientService.isLoggedIn();
    }

    public String getQrCodeContent() {
        return clientService.getQrCodeContent();
    }

    // ---------- 消息处理 ----------

    /**
     * 处理收到的消息。三行骨架：分类 → 路由 → 分发。
     */
    private void handleMessage(WeixinMessage message) {
        UnifiedContext ctx = classifier.classify(message);

        if (ctx.userId() == null || ctx.userId().isEmpty()) {
            log.warn("无法回复: 用户 ID 为空");
            return;
        }

        clientService.startTyping(ctx.userId());

        try {
            List<MessageResponse> responses = router.route(ctx);
            boolean hasDeferred = dispatcher.dispatch(ctx.userId(), responses);

            if (!hasDeferred) {
                clientService.stopTyping(ctx.userId());
            }
        } catch (Exception e) {
            log.error("处理消息失败", e);
            clientService.stopTyping(ctx.userId());
            try {
                clientService.sendText(ctx.userId(), "对话服务暂时不可用，请稍后再试。");
            } catch (Exception ignored) {
            }
        }
    }
}
