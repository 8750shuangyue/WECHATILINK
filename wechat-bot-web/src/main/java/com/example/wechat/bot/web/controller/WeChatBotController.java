package com.example.wechat.bot.web.controller;

import com.example.wechat.bot.common.util.QRCodeUtil;
import com.example.wechat.bot.core.model.dto.ChatApiStatus;
import com.example.wechat.bot.core.service.ChatApiStatsService;
import com.example.wechat.bot.core.service.ChatCompletionService;
import com.example.wechat.bot.adapter.wechat.WeChatBotService;
import com.example.wechat.bot.web.dto.ChatResponse;
import com.example.wechat.bot.web.dto.LoginResponse;
import com.example.wechat.bot.web.dto.QRCodeResponse;
import com.example.wechat.bot.web.dto.StatusResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wechat")
public class WeChatBotController {

    @Autowired
    private WeChatBotService weChatBotService;

    @Autowired
    private ChatCompletionService chatCompletionService;

    @Autowired
    private ChatApiStatsService chatApiStatsService;

    @PostMapping("/login")
    public LoginResponse startLogin() {
        try {
            String qrContent = weChatBotService.startLogin();
            String qrBase64 = QRCodeUtil.generateQRCodeBase64(qrContent, 300, 300);
            return LoginResponse.success(qrBase64, qrContent);
        } catch (Exception e) {
            return LoginResponse.failure(e.getMessage());
        }
    }

    @GetMapping("/status")
    public StatusResponse getStatus() {
        ChatApiStatus chatApi = chatApiStatsService.getStatusSnapshot();
        if (weChatBotService.isLoggedIn()) {
            return StatusResponse.logged_in(chatApi);
        } else {
            return StatusResponse.not_logged_in(chatApi);
        }
    }

    @GetMapping("/qrcode")
    public QRCodeResponse getQRCode() {
        try {
            String qrContent = weChatBotService.getQrCodeContent();
            if (qrContent != null) {
                String qrBase64 = QRCodeUtil.generateQRCodeBase64(qrContent, 300, 300);
                return QRCodeResponse.success(qrBase64);
            } else {
                return QRCodeResponse.failure("请先调用 /api/wechat/login 获取二维码");
            }
        } catch (Exception e) {
            return QRCodeResponse.failure(e.getMessage());
        }
    }

    @GetMapping("/test-chat")
    public ChatResponse testChat(
            @RequestParam(defaultValue = "你好") String msg,
            @RequestParam(defaultValue = "test-user") String userId) {
        try {
            String reply = chatCompletionService.chat(userId, msg);
            return ChatResponse.success(reply);
        } catch (Exception e) {
            String cause = e.getCause() != null ? e.getCause().getMessage() : null;
            return ChatResponse.failure(e.getMessage(), cause);
        }
    }
}
