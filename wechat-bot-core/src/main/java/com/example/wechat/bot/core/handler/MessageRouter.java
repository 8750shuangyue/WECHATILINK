package com.example.wechat.bot.core.handler;

import com.example.wechat.bot.core.model.dto.MessageResponse;
import com.example.wechat.bot.core.model.dto.UnifiedContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息路由器——接收 {@link UnifiedContext} 转发给 {@link UnifiedMessageHandler}。
 * 当前只有统一处理器，保留该层是为了日后扩展插件系统或多处理器编排时，
 * 路由点不涉及核心处理器的改动。
 */
@Component
public class MessageRouter {

    private final UnifiedMessageHandler handler;

    public MessageRouter(UnifiedMessageHandler handler) {
        this.handler = handler;
    }

    /**
     * 路由消息到对应处理器
     *
     * @param ctx 消息上下文
     * @return 响应列表
     */
    public List<MessageResponse> route(UnifiedContext ctx) {
        return handler.handle(ctx);
    }
}
