package com.example.wechat.bot.web.dto;

import com.example.wechat.bot.core.model.dto.ChatApiStatus;

public record StatusResponse(
        boolean loggedIn,
        String interactionMode,
        String message,
        ChatApiStatus chatApi
) {
    public static StatusResponse logged_in(ChatApiStatus chatApi) {
        return new StatusResponse(
                true,
                "wechat-client-chat",
                "已登录，请直接在微信客户端聊天框中给机器人发送消息。",
                chatApi
        );
    }

    public static StatusResponse not_logged_in(ChatApiStatus chatApi) {
        return new StatusResponse(
                false,
                "wechat-client-chat",
                "未登录，请先扫码登录。",
                chatApi
        );
    }
}
