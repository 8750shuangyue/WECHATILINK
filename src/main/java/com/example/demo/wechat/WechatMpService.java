package com.example.demo.wechat;

import com.example.demo.agent.AgentResult;
import com.example.demo.agent.AgentService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutTextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WechatMpService {

    private static final Logger log = LoggerFactory.getLogger(WechatMpService.class);
    private final AgentService agentService;

    public WechatMpService(AgentService agentService) {
        this.agentService = agentService;
    }

    public String handleMessage(WxMpXmlMessage message) {
        String fromUser = message.getFromUser();
        String msgType = message.getMsgType();
        String content = message.getContent();

        log.info("[WechatMP] Received message: type={}, from={}, content={}",
                msgType, fromUser, content != null ? content.substring(0, Math.min(50, content.length())) : "null");

        try {
            if ("text".equals(msgType)) {
                return handleTextMessage(fromUser, content);
            } else if ("image".equals(msgType)) {
                return "图片已收到，请发送文字描述您想了解的内容。\n\n您可以：\n• 发送「诊断」进行病虫害识别\n• 发送护理问题获取建议\n• 发送「提醒」查看护理计划";
            } else if ("voice".equals(msgType)) {
                String recognition = message.getRecognition();
                if (recognition != null && !recognition.isEmpty()) {
                    return handleTextMessage(fromUser, recognition);
                }
                return "语音已收到，请发送文字消息获取帮助。";
            } else if ("event".equals(msgType)) {
                return handleEvent(message);
            }
            return getWelcomeMessage();
        } catch (Exception e) {
            log.error("[WechatMP] Error handling message: {}", e.getMessage(), e);
            return "抱歉，处理您的消息时出现错误，请稍后再试。";
        }
    }

    private String handleTextMessage(String userId, String text) {
        AgentResult result = agentService.runAgent(userId, text, null, null);
        if (result.isSuccess()) {
            String reply = result.getReply();
            if (reply != null && reply.length() > 600) {
                reply = reply.substring(0, 600) + "\n\n...（内容过长已截断，请打开APP查看完整回复）";
            }
            return reply;
        }
        return "抱歉，AI暂时无法回复，请稍后再试。";
    }

    private String handleEvent(WxMpXmlMessage message) {
        String event = message.getEvent();
        log.info("[WechatMP] Event: {}, key: {}", event, message.getEventKey());

        return switch (event) {
            case "subscribe" -> getWelcomeMessage();
            case "CLICK" -> handleMenuClick(message);
            default -> "";
        };
    }

    private String handleMenuClick(WxMpXmlMessage message) {
        String eventKey = message.getEventKey();
        return switch (eventKey) {
            case "AI_CHAT" -> "请直接发送您的问题，我会为您解答宠物/植物护理相关问题。\n\n您可以：\n• 询问宠物喂养/疾病/训练问题\n• 上传植物照片识别品种\n• 查询天气和护理建议";
            case "REMINDERS" -> "护理提醒功能：\n\n请发送「创建提醒」来设置护理计划\n或发送「查看提醒」查看现有提醒\n\n支持：浇水、施肥、驱虫、疫苗、喂药等提醒";
            case "MY_PROFILE" -> "我的档案：\n\n请打开Sekai PetPlant APP查看和管理您的宠物/植物档案。\n\n在APP中您可以：\n• 创建宠物/植物档案\n• 查看护理记录\n• 上传成长照片";
            default -> getWelcomeMessage();
        };
    }

    private String getWelcomeMessage() {
        return """
                🐾🌿 欢迎关注 Sekai PetPlant！

                我是您的AI宠物/植物护理助手，可以帮您：
                🌤 查询天气和护理建议
                🩺 宠物症状咨询和分诊
                🌱 植物病虫害识别
                🍖 宠物食品安全查询
                💊 用药记录和提醒
                🏥 查找附近宠物医院

                直接发送消息即可开始对话！
                发送「帮助」了解更多功能。
                """;
    }
}
