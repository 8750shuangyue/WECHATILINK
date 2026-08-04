package com.example.demo.wechat;

import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/wechat/mp")
public class WechatMpController {

    private static final Logger log = LoggerFactory.getLogger(WechatMpController.class);
    private final WxMpService wxMpService;
    private final WechatMpService wechatMpService;

    public WechatMpController(WxMpService wxMpService, WechatMpService wechatMpService) {
        this.wxMpService = wxMpService;
        this.wechatMpService = wechatMpService;
    }

    @GetMapping("/portal")
    public String verifyServer(@RequestParam("signature") String signature,
                                @RequestParam("timestamp") String timestamp,
                                @RequestParam("nonce") String nonce,
                                @RequestParam("echostr") String echostr) {
        log.info("[WechatMP] Server verification request");

        if (wxMpService.checkSignature(timestamp, nonce, signature)) {
            log.info("[WechatMP] Server verification success");
            return echostr;
        }
        log.warn("[WechatMP] Server verification failed");
        return "verification failed";
    }

    @PostMapping("/portal")
    public String handleMessage(@RequestBody String requestBody,
                                 @RequestParam("signature") String signature,
                                 @RequestParam("timestamp") String timestamp,
                                 @RequestParam("nonce") String nonce,
                                 @RequestParam(name = "encrypt_type", required = false) String encryptType,
                                 @RequestParam(name = "msg_signature", required = false) String msgSignature) {
        log.info("[WechatMP] Received message webhook");

        if (!wxMpService.checkSignature(timestamp, nonce, signature)) {
            log.warn("[WechatMP] Signature check failed");
            return "signature error";
        }

        try {
            WxMpXmlMessage message;
            if (encryptType != null && "aes".equals(encryptType)) {
                message = WxMpXmlMessage.fromEncryptedXml(
                        requestBody, wxMpService.getWxMpConfigStorage(), timestamp, nonce, msgSignature);
            } else {
                message = WxMpXmlMessage.fromXml(requestBody);
            }

            String replyContent = wechatMpService.handleMessage(message);

            if (replyContent == null || replyContent.isEmpty()) {
                return "";
            }

            WxMpXmlOutMessage outMessage = WxMpXmlOutMessage.TEXT()
                    .content(replyContent)
                    .fromUser(message.getToUser())
                    .toUser(message.getFromUser())
                    .build();

            if (encryptType != null && "aes".equals(encryptType)) {
                return outMessage.toEncryptedXml(wxMpService.getWxMpConfigStorage());
            }
            return outMessage.toXml();
        } catch (Exception e) {
            log.error("[WechatMP] Error processing webhook: {}", e.getMessage(), e);
            return "";
        }
    }
}
