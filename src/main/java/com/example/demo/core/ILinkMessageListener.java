package com.example.demo.core;

import com.example.demo.asr.AudioConverterService;
import com.example.demo.asr.DashScopeAsrService;
import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.UserSession;
import com.example.demo.chat.UserSessionService;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.agent.AgentResult;
import com.example.demo.agent.AgentService;
import com.example.demo.tts.XfTtsService;
import com.example.demo.vision.VisionService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ILinkMessageListener {

    private static final Logger logger = LoggerFactory.getLogger(ILinkMessageListener.class);

    private ILinkClient client;
    private final FileStorageService fileStorageService;
    private final DashScopeConfig config;
    private final XfTtsService xfTtsService;
    private final DashScopeAsrService dashScopeAsrService;
    private final AudioConverterService audioConverterService;
    private final UserSessionService userSessionService;
    private final VisionService visionService;
    private final ChatMemoryService chatMemoryService;
    private final AgentService agentService;
    private final Set<String> processedMessageIds = new HashSet<>();
    private final AtomicBoolean isLoggedIn = new AtomicBoolean(false);

    public ILinkMessageListener(FileStorageService fileStorageService, 
                                DashScopeConfig config, XfTtsService xfTtsService,
                                DashScopeAsrService dashScopeAsrService, AudioConverterService audioConverterService,
                                UserSessionService userSessionService, VisionService visionService,
                                ChatMemoryService chatMemoryService, AgentService agentService) {
        this.fileStorageService = fileStorageService;
        this.config = config;
        this.xfTtsService = xfTtsService;
        this.dashScopeAsrService = dashScopeAsrService;
        this.audioConverterService = audioConverterService;
        this.userSessionService = userSessionService;
        this.visionService = visionService;
        this.chatMemoryService = chatMemoryService;
        this.agentService = agentService;
    }

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                initClient();
                login();
                receiveAndReply();
            } catch (Exception e) {
                logger.error("ILink message listener failed", e);
            }
        }, "ILink-Message-Listener").start();
    }

    private void initClient() {
        this.client = ILinkClient.builder()
                .config(com.github.wechat.ilink.sdk.core.config.ILinkConfig.builder().build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(com.github.wechat.ilink.sdk.core.login.LoginContext context) {
                        logger.info("ILink login successful");
                        System.out.println("登录成功！");
                        isLoggedIn.set(true);
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        logger.error("ILink login failed", throwable);
                    }
                })
                .build();
    }

    private void login() throws Exception {
        logger.info("Starting ILink login...");
        
        String qrCode = client.executeLogin();
        System.out.println("请扫码登录: " + qrCode);
        logger.info("QR code generated, waiting for scan...");

        while (!isLoggedIn.get()) {
            Thread.sleep(3000);
        }
    }

    private void receiveAndReply() throws Exception {
        logger.info("ILink message listener started");
        while (true) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                
                if (!messages.isEmpty()) {
                    var msg = messages.get(0);
                    
                    String messageId = extractMessageId(msg);
                    if (messageId != null && processedMessageIds.contains(messageId)) {
                        logger.debug("Message {} already processed, skipping", messageId);
                        continue;
                    }
                    if (messageId != null) {
                        processedMessageIds.add(messageId);
                    }
                    
                    String userId = extractUserId(msg);
                    
                    String textContent = extractTextContent(msg);
                    byte[] imageContent = extractImageContent(msg);
                    byte[] voiceContent = extractVoiceContent(msg);
                    byte[] fileContent = extractFileContent(msg);
                    String fileName = extractFileName(msg);

                    if (textContent == null && imageContent == null && voiceContent == null && fileContent == null) {
                        logger.warn("Message content is empty, skipping");
                        continue;
                    }

                    MessageReply reply = processMessage(userId, textContent, imageContent, voiceContent, fileContent, fileName);
                    if (reply == null) {
                        logger.info("No reply needed for message from user {}", userId);
                        continue;
                    }
                    logger.info("Sending reply to user {}: {}", userId, reply.getText());
                    
                    if (userId != null) {
                        if (reply.hasImage()) {
                            try {
                                if ("mp3".equals(reply.getImageExtension())) {
                                    logger.info("Sending audio file, size: {} bytes, to user: {}",
                                        reply.getImageData().length, userId);
                                    String audioFileName = java.nio.file.Paths.get(reply.getImageUrl()).getFileName().toString();
                                    
                                    boolean sentAsVoice = false;
                                    try {
                                        java.lang.reflect.Method sendVoiceMethod = client.getClass().getMethod("sendVoice", String.class, byte[].class);
                                        sendVoiceMethod.invoke(client, userId, reply.getImageData());
                                        logger.info("Voice message sent successfully via sendVoice");
                                        sentAsVoice = true;
                                    } catch (NoSuchMethodException e) {
                                        logger.debug("sendVoice method not found, falling back to sendFile");
                                    }
                                    
                                    if (!sentAsVoice) {
                                        client.sendFile(userId, reply.getImageData(), audioFileName, "audio/mp3");
                                        logger.info("Audio file sent successfully");
                                    }
                                } else {
                                    logger.info("Uploading image to send, size: {} bytes, to user: {}",
                                        reply.getImageData().length, userId);
                                    client.sendImage(userId, reply.getImageData(), 
                                        reply.getImageExtension(), reply.getImageUrl());
                                    logger.info("Image sent successfully");
                                }
                            } catch (Exception e) {
                                logger.error("Failed to send media, falling back to text", e);
                                if (reply.getText() != null && !reply.getText().isEmpty()) {
                                    client.sendText(userId, reply.getText());
                                }
                            }
                        } else if (reply.getText() != null && !reply.getText().isEmpty()) {
                            if (reply.isNeedTts()) {
                                sendReplyWithTtsFallback(userId, reply.getText());
                            } else {
                                try {
                                    client.sendText(userId, reply.getText());
                                    logger.info("Text message sent");
                                } catch (Exception e) {
                                    logger.error("Failed to send text message", e);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing message", e);
            }
            Thread.sleep(3000);
        }
    }

    private MessageReply processMessage(String userId, String textContent, byte[] imageContent, byte[] voiceContent, byte[] fileContent, String fileName) {
        try {
            if (voiceContent != null) {
                logger.info("Received voice message, size: {} bytes", voiceContent.length);
                String recognizedText = recognizeVoice(voiceContent);
                logger.info("Voice recognition result: {}", recognizedText);
                if (recognizedText != null && !recognizedText.isEmpty()) {
                    textContent = recognizedText;
                    logger.info("Voice message converted to text: {}", textContent);
                } else {
                    logger.warn("Voice recognition failed or returned empty result");
                    return new MessageReply("抱歉，语音识别失败，请重试或使用文字输入");
                }
            }

            if (textContent != null && imageContent != null) {
                logger.info("Received both text and image in same message");
                String imageBase64 = Base64.getEncoder().encodeToString(imageContent);
                if (userId != null) {
                    userSessionService.storePendingImage(userId, imageBase64);
                }
                
                try {
                    String analysis = visionService.analyzeImageWithCustomPrompt(imageContent, "请描述这张图片的内容");
                    if (userId != null) {
                        chatMemoryService.saveMessagePair(userId, "[图片]", analysis);
                        userSessionService.storePendingImage(userId, imageBase64);
                        UserSession session = userSessionService.getSession(userId);
                        if (session != null) {
                            session.setImageDescription(analysis);
                            session.setImageAnalyzed(true);
                            userSessionService.saveSession(session);
                        }
                    }
                    
                    String combinedMessage = "图片内容：" + analysis + "\n\n用户问题：" + textContent;
                    logger.info("Processing combined image+text via AgentService");
                    AgentResult agentResult = agentService.runAgent(userId, combinedMessage, null, 
                            msg -> sendProgressMessage(userId, msg));
                    return buildReplyFromAgentResult(agentResult);
                } catch (Exception e) {
                    logger.error("Failed to analyze image", e);
                    return new MessageReply("处理图片时发生错误：" + e.getMessage());
                }
            }

            if (textContent != null) {
                logger.info("Processing text message: '{}', userId: {}", 
                        textContent.length() > 50 ? textContent.substring(0, 50) + "..." : textContent, userId);
                if (userId != null && userSessionService.hasPendingImage(userId)) {
                    logger.info("Found pending image in session for user {}", userId);
                    if (userSessionService.hasUnanalyzedImage(userId)) {
                        logger.info("Text received with unanalyzed pending image for user {}, analyzing first", userId);
                        try {
                            String pendingImageBase64 = userSessionService.getPendingImageBase64(userId);
                            logger.info("Pending image base64 length: {}", pendingImageBase64 != null ? pendingImageBase64.length() : 0);
                            if (pendingImageBase64 != null && !pendingImageBase64.isEmpty()) {
                                logger.info("Decoding image base64...");
                                byte[] decodedImage = Base64.getDecoder().decode(pendingImageBase64);
                                logger.info("Decoded image size: {} bytes", decodedImage.length);
                                
                                logger.info("Calling visionService.analyzeImageWithCustomPrompt...");
                                String analysis = visionService.analyzeImageWithCustomPrompt(decodedImage, "请描述这张图片的内容");
                                logger.info("Image analysis result length: {}", analysis != null ? analysis.length() : 0);
                                
                                if (analysis != null && !analysis.isEmpty()) {
                                    chatMemoryService.saveMessagePair(userId, "[图片]", analysis);
                                    UserSession session = userSessionService.getSession(userId);
                                    if (session != null) {
                                        session.setImageDescription(analysis);
                                        session.setImageAnalyzed(true);
                                        userSessionService.saveSession(session);
                                    }
                                    logger.info("Image analyzed successfully: {}", analysis.length() > 50 ? analysis.substring(0, 50) + "..." : analysis);
                                } else {
                                    logger.warn("Image analysis returned empty result");
                                }
                            } else {
                                logger.warn("Pending image base64 is null or empty");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to analyze pending image: {}", e.getMessage(), e);
                        }
                        
                        String imageDescription = userSessionService.getImageDescription(userId);
                        String combinedMessage;
                        if (imageDescription != null && !imageDescription.isEmpty()) {
                            combinedMessage = "图片内容：" + imageDescription + "\n\n用户问题：" + textContent;
                        } else {
                            combinedMessage = "图片内容：[待分析图片]\n\n用户问题：" + textContent;
                        }
                        logger.info("Processing combined image+text via AgentService (image needs analysis)");
                        AgentResult agentResult = agentService.runAgent(userId, combinedMessage, null, 
                                msg -> sendProgressMessage(userId, msg));
                        return buildReplyFromAgentResult(agentResult);
                    } else {
                        logger.info("Text received with already analyzed image for user {}, injecting image context", userId);
                        String imageDescription = userSessionService.getImageDescription(userId);
                        String combinedMessage;
                        if (imageDescription != null && !imageDescription.isEmpty()) {
                            combinedMessage = "图片内容：" + imageDescription + "\n\n用户问题：" + textContent;
                        } else {
                            combinedMessage = textContent;
                        }
                        
                        String fileInfo = null;
                        if (userId != null && userSessionService.hasPendingFile(userId)) {
                            String pendingFileUrl = userSessionService.getPendingFileUrl(userId);
                            String pendingFileName = userSessionService.getPendingFileName(userId);
                            if (pendingFileUrl != null && pendingFileName != null) {
                                fileInfo = "fileName=" + pendingFileName + ",fileUrl=" + pendingFileUrl;
                                logger.info("Found pending file in session while processing image context: {}", fileInfo);
                                if (userSessionService.hasUnanalyzedFile(userId)) {
                                    userSessionService.markFileAsAnalyzed(userId);
                                }
                            }
                        }
                        
                        AgentResult agentResult = agentService.runAgent(userId, combinedMessage, fileInfo, 
                                msg -> sendProgressMessage(userId, msg));
                        return buildReplyFromAgentResult(agentResult);
                    }
                }
                
                logger.info("Processing text-only message via AgentService");
                String fileInfo = null;
                if (userId != null && userSessionService.hasPendingFile(userId)) {
                    String pendingFileUrl = userSessionService.getPendingFileUrl(userId);
                    String pendingFileName = userSessionService.getPendingFileName(userId);
                    if (pendingFileUrl != null && pendingFileName != null) {
                        fileInfo = "fileName=" + pendingFileName + ",fileUrl=" + pendingFileUrl;
                        logger.info("Found pending file in session: {}", fileInfo);
                        if (userSessionService.hasUnanalyzedFile(userId)) {
                            userSessionService.markFileAsAnalyzed(userId);
                        }
                    } else {
                        logger.warn("Pending file exists but url or name is null: url={}, name={}", pendingFileUrl, pendingFileName);
                    }
                } else {
                    logger.info("No pending file found for user {}", userId);
                }
                
                String imageDescription = null;
                String effectiveMessage = textContent;
                if (userId != null) {
                    imageDescription = userSessionService.getImageDescription(userId);
                    if (imageDescription != null && !imageDescription.isEmpty()) {
                        boolean mentionsImage = textContent.contains("图片") || textContent.contains("图") || 
                                textContent.contains("刚才") || textContent.contains("之前") || 
                                textContent.contains("还记得");
                        if (mentionsImage) {
                            effectiveMessage = "图片内容：" + imageDescription + "\n\n用户问题：" + textContent;
                        }
                    }
                }
                
                logger.info("Calling agentService.runAgent with fileInfo: {}, effectiveMessage: {}", 
                        fileInfo, effectiveMessage.length() > 50 ? effectiveMessage.substring(0, 50) + "..." : effectiveMessage);
                AgentResult agentResult = agentService.runAgent(userId, effectiveMessage, fileInfo, 
                        msg -> sendProgressMessage(userId, msg));
                return buildReplyFromAgentResult(agentResult);
            }

            if (imageContent != null) {
                logger.info("Received image-only message, storing in session for user {}", userId);
                String imageBase64 = Base64.getEncoder().encodeToString(imageContent);
                userSessionService.storePendingImage(userId, imageBase64);
                return null;
            }

            if (fileContent != null) {
                logger.info("Received file message, name: {}, size: {} bytes", fileName, fileContent.length);
                String fileUrl = saveFileToLocal(fileContent, fileName);
                
                if (userId != null) {
                    userSessionService.storePendingFile(userId, fileUrl, fileName);
                }
                
                if (textContent != null && !textContent.trim().isEmpty()) {
                    String userQuery = textContent.trim();
                    AgentResult agentResult = agentService.runAgent(userId, userQuery, 
                            "fileName=" + fileName + ",fileUrl=" + fileUrl, 
                            msg -> sendProgressMessage(userId, msg));
                    if (userId != null) {
                        userSessionService.markFileAsAnalyzed(userId);
                    }
                    return buildReplyFromAgentResult(agentResult);
                } else {
                    return new MessageReply("文件已收到，请发送您的指令，比如“分析这个文档”或“总结这份文件的内容”");
                }
            }

            return new MessageReply("抱歉，无法处理该消息");
        } catch (Exception e) {
            logger.error("Error processing message", e);
            return new MessageReply("处理消息时发生错误: " + e.getMessage());
        }
    }
    
    private String saveFileToLocal(byte[] fileContent, String fileName) throws Exception {
        java.nio.file.Path uploadPath = java.nio.file.Paths.get("uploads").toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(uploadPath)) {
            java.nio.file.Files.createDirectories(uploadPath);
        }
        
        String uniqueFileName = System.currentTimeMillis() + "_" + fileName;
        java.nio.file.Path filePath = uploadPath.resolve(uniqueFileName).normalize();
        java.nio.file.Files.write(filePath, fileContent);
        
        return "file:///" + filePath.toString();
    }
    
    private MessageReply buildReplyFromAgentResult(AgentResult agentResult) {
        if (agentResult.isSuccess()) {
            if (agentResult.getAudioFilePath() != null) {
                try {
                    byte[] audioData = java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(agentResult.getAudioFilePath()));
                    String audioFileName = java.nio.file.Paths.get(agentResult.getAudioFilePath()).getFileName().toString();
                    return new MessageReply(agentResult.getReply(), audioData, "mp3", agentResult.getAudioFilePath(), false);
                } catch (Exception e) {
                    logger.error("Failed to read audio file", e);
                    return new MessageReply(agentResult.getReply());
                }
            }
            if (agentResult.getImageUrl() != null) {
                try {
                    byte[] imageData = java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(agentResult.getImageUrl()));
                    String extension = extractImageExtension(agentResult.getImageUrl());
                    return new MessageReply(agentResult.getReply(), imageData, extension, agentResult.getImageUrl());
                } catch (Exception e) {
                    logger.error("Failed to read image file", e);
                    return new MessageReply(agentResult.getReply());
                }
            }
            return new MessageReply(agentResult.getReply());
        } else {
            return new MessageReply(agentResult.getErrorMessage());
        }
    }
    
    private void sendProgressMessage(String userId, String message) {
        if (userId == null || message == null || message.isEmpty()) {
            return;
        }
        try {
            client.sendText(userId, message);
            logger.info("Progress message sent to user {}: {}", userId, message);
        } catch (Exception e) {
            logger.error("Failed to send progress message to user {}", userId, e);
        }
    }

    private String recognizeVoice(byte[] voiceData) {
        try {
            logger.info("Starting voice recognition (DashScope), data size: {} bytes", voiceData.length);

            StringBuilder hexHeader = new StringBuilder("Voice data header: ");
            for (int i = 0; i < Math.min(16, voiceData.length); i++) {
                hexHeader.append(String.format("%02X ", voiceData[i] & 0xFF));
            }
            logger.info(hexHeader.toString());

            byte[] wavData = audioConverterService.convertToWav16k16bitMono(voiceData, "auto");
            logger.info("Audio converted to WAV, size: {} bytes", wavData.length);

            String recognizedText = dashScopeAsrService.recognize(wavData);
            logger.info("Voice recognition completed (DashScope): {}", recognizedText);
            return recognizedText;
        } catch (Exception e) {
            logger.error("Voice recognition failed (DashScope)", e);
            return null;
        }
    }
    
    private String extractImageExtension(String imageUrl) {
        if (imageUrl == null) {
            return "png";
        }
        int lastDot = imageUrl.lastIndexOf('.');
        if (lastDot > 0 && lastDot < imageUrl.length() - 1) {
            String ext = imageUrl.substring(lastDot + 1).toLowerCase();
            if (ext.contains("?")) {
                ext = ext.substring(0, ext.indexOf('?'));
            }
            return ext;
        }
        return "png";
    }
    
    private static class MessageReply {
        private final String text;
        private final byte[] imageData;
        private final String imageExtension;
        private final String imageUrl;
        private final boolean needTts;
        
        public MessageReply(String text) {
            this(text, null, null, null, false);
        }
        
        public MessageReply(String text, boolean needTts) {
            this(text, null, null, null, needTts);
        }
        
        public MessageReply(String text, byte[] imageData, String imageExtension, String imageUrl) {
            this(text, imageData, imageExtension, imageUrl, false);
        }
        
        public MessageReply(String text, byte[] imageData, String imageExtension, String imageUrl, boolean needTts) {
            this.text = text;
            this.imageData = imageData;
            this.imageExtension = imageExtension;
            this.imageUrl = imageUrl;
            this.needTts = needTts;
        }
        
        public String getText() {
            return text;
        }
        
        public byte[] getImageData() {
            return imageData;
        }
        
        public String getImageExtension() {
            return imageExtension;
        }
        
        public String getImageUrl() {
            return imageUrl;
        }
        
        public boolean hasImage() {
            return imageData != null && imageData.length > 0;
        }
        
        public boolean isNeedTts() {
            return needTts;
        }
    }

    private String extractUserId(Object msg) {
        try {
            Method getFromUserIdMethod = msg.getClass().getMethod("getFrom_user_id");
            return (String) getFromUserIdMethod.invoke(msg);
        } catch (Exception e) {
            logger.warn("Failed to extract userId", e);
            return null;
        }
    }

    private String extractMessageId(Object msg) {
        try {
            Method getMsgIdMethod = msg.getClass().getMethod("getMessage_id");
            Object msgId = getMsgIdMethod.invoke(msg);
            return msgId != null ? msgId.toString() : null;
        } catch (Exception e) {
            try {
                Method getIdMethod = msg.getClass().getMethod("getId");
                Object id = getIdMethod.invoke(msg);
                return id != null ? id.toString() : null;
            } catch (Exception ex) {
                logger.debug("Failed to extract messageId", ex);
                return null;
            }
        }
    }

    private String extractTextContent(Object msg) {
        try {
            Method getItemListMethod = msg.getClass().getMethod("getItem_list");
            Object itemList = getItemListMethod.invoke(msg);

            if (!(itemList instanceof List<?>)) {
                return null;
            }

            List<?> items = (List<?>) itemList;
            for (Object item : items) {
                try {
                    Method getTypeMethod = item.getClass().getMethod("getType");
                    Object typeObj = getTypeMethod.invoke(item);
                    if (typeObj instanceof Integer && (Integer) typeObj == 1) {
                        Method getTextItemMethod = item.getClass().getMethod("getText_item");
                        Object textItem = getTextItemMethod.invoke(item);
                        if (textItem != null) {
                            Method getTextMethod = textItem.getClass().getMethod("getText");
                            Object text = getTextMethod.invoke(textItem);
                            if (text instanceof String && !((String) text).trim().isEmpty()) {
                                return (String) text;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to extract text from item", e);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract text content", e);
        }
        return null;
    }

    private byte[] extractImageContent(Object msg) {
        try {
            Method getItemListMethod = msg.getClass().getMethod("getItem_list");
            Object itemList = getItemListMethod.invoke(msg);

            if (!(itemList instanceof List<?>)) {
                return null;
            }

            List<?> items = (List<?>) itemList;
            for (Object item : items) {
                try {
                    Method getTypeMethod = item.getClass().getMethod("getType");
                    Object typeObj = getTypeMethod.invoke(item);
                    if (typeObj instanceof Integer && (Integer) typeObj == 2) {
                        Method getImageItemMethod = item.getClass().getMethod("getImage_item");
                        Object imageObj = getImageItemMethod.invoke(item);
                        
                        if (imageObj != null) {
                            try {
                                Method getMediaMethod = imageObj.getClass().getMethod("getMedia");
                                Object mediaObj = getMediaMethod.invoke(imageObj);
                                
                                if (mediaObj != null) {
                                    Method getEncryptQueryParamMethod = mediaObj.getClass().getMethod("getEncrypt_query_param");
                                    Object encryptQueryParam = getEncryptQueryParamMethod.invoke(mediaObj);
                                    
                                    Method getAesKeyMethod = mediaObj.getClass().getMethod("getAes_key");
                                    Object aesKey = getAesKeyMethod.invoke(mediaObj);
                                    
                                    if (encryptQueryParam instanceof String && aesKey instanceof String) {
                                        logger.info("Downloading encrypted image using ILinkClient.downloadMedia");
                                        Object cdnMedia = createCDNMedia(encryptQueryParam.toString(), aesKey.toString());
                                        Method downloadMediaMethod = client.getClass().getMethod("downloadMedia", 
                                            Class.forName("com.github.wechat.ilink.sdk.core.model.CDNMedia"));
                                        Object result = downloadMediaMethod.invoke(client, cdnMedia);
                                        
                                        if (result instanceof byte[]) {
                                            byte[] imageBytes = (byte[]) result;
                                            logger.info("Image downloaded successfully, size: {} bytes", imageBytes.length);
                                            return imageBytes;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Failed to download encrypted image", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to process item", e);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract image content", e);
        }
        return null;
    }

    private byte[] extractVoiceContent(Object msg) {
        try {
            Method getItemListMethod = msg.getClass().getMethod("getItem_list");
            Object itemList = getItemListMethod.invoke(msg);

            if (!(itemList instanceof List<?>)) {
                return null;
            }

            List<?> items = (List<?>) itemList;
            for (Object item : items) {
                try {
                    Method getTypeMethod = item.getClass().getMethod("getType");
                    Object typeObj = getTypeMethod.invoke(item);
                    if (typeObj instanceof Integer && (Integer) typeObj == 3) {
                        Method getVoiceItemMethod = item.getClass().getMethod("getVoice_item");
                        Object voiceObj = getVoiceItemMethod.invoke(item);

                        if (voiceObj != null) {
                            // Log voice item metadata
                            try {
                                Method getEncodeTypeMethod = voiceObj.getClass().getMethod("getEncode_type");
                                Method getSampleRateMethod = voiceObj.getClass().getMethod("getSample_rate");
                                Method getPlaytimeMethod = voiceObj.getClass().getMethod("getPlaytime");
                                Object encodeType = getEncodeTypeMethod.invoke(voiceObj);
                                Object sampleRate = getSampleRateMethod.invoke(voiceObj);
                                Object playtime = getPlaytimeMethod.invoke(voiceObj);
                                logger.info("Voice item metadata: encode_type={}, sample_rate={}, playtime={}",
                                    encodeType, sampleRate, playtime);
                            } catch (Exception e) {
                                logger.debug("Could not read voice item metadata", e);
                            }

                            // Try downloadVoiceFromMessageItem first (different download path)
                            try {
                                Class<?> messageItemClass = Class.forName("com.github.wechat.ilink.sdk.core.model.MessageItem");
                                Method downloadVoiceMethod = client.getClass().getMethod("downloadVoiceFromMessageItem", messageItemClass);
                                Object result = downloadVoiceMethod.invoke(client, item);
                                if (result instanceof byte[]) {
                                    byte[] voiceBytes = (byte[]) result;
                                    logger.info("Voice downloaded via downloadVoiceFromMessageItem, size: {} bytes", voiceBytes.length);
                                    return voiceBytes;
                                }
                            } catch (Exception e) {
                                logger.debug("downloadVoiceFromMessageItem failed, falling back to downloadMedia: {}", e.getMessage());
                            }

                            // Fallback to downloadMedia
                            try {
                                Method getMediaMethod = voiceObj.getClass().getMethod("getMedia");
                                Object mediaObj = getMediaMethod.invoke(voiceObj);

                                if (mediaObj != null) {
                                    Method getEncryptQueryParamMethod = mediaObj.getClass().getMethod("getEncrypt_query_param");
                                    Object encryptQueryParam = getEncryptQueryParamMethod.invoke(mediaObj);

                                    Method getAesKeyMethod = mediaObj.getClass().getMethod("getAes_key");
                                    Object aesKey = getAesKeyMethod.invoke(mediaObj);

                                    if (encryptQueryParam instanceof String && aesKey instanceof String) {
                                        logger.info("Downloading encrypted voice using ILinkClient.downloadMedia");
                                        Object cdnMedia = createCDNMedia(encryptQueryParam.toString(), aesKey.toString());
                                        Method downloadMediaMethod = client.getClass().getMethod("downloadMedia",
                                            Class.forName("com.github.wechat.ilink.sdk.core.model.CDNMedia"));
                                        Object result = downloadMediaMethod.invoke(client, cdnMedia);

                                        if (result instanceof byte[]) {
                                            byte[] voiceBytes = (byte[]) result;
                                            logger.info("Voice downloaded via downloadMedia, size: {} bytes", voiceBytes.length);
                                            return voiceBytes;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Failed to download encrypted voice", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to process item", e);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract voice content", e);
        }
        return null;
    }

    private byte[] extractFileContent(Object msg) {
        try {
            Method getItemListMethod = msg.getClass().getMethod("getItem_list");
            Object itemList = getItemListMethod.invoke(msg);

            if (!(itemList instanceof List<?>)) {
                logger.debug("Item list is not a List: {}", itemList != null ? itemList.getClass().getName() : "null");
                return null;
            }

            List<?> items = (List<?>) itemList;
            logger.info("Message has {} items", items.size());
            
            for (Object item : items) {
                try {
                    Method getTypeMethod = item.getClass().getMethod("getType");
                    Object typeObj = getTypeMethod.invoke(item);
                    logger.info("Item type: {} (class: {})", typeObj, item.getClass().getName());
                    
                    if (typeObj instanceof Integer && (Integer) typeObj == 4) {
                        Object fileObj = getItemField(item, "getFile_item", "getItem", "getFileItem", "getAppMsgItem", "getAttachItem");
                        
                        if (fileObj != null) {
                            logger.info("File item class: {}", fileObj.getClass().getName());
                            logAllMethods(fileObj);
                            
                            try {
                                Object mediaObj = getItemField(fileObj, "getMedia", "getMediaObj", "getFileMedia");
                                
                                if (mediaObj != null) {
                                    logger.info("Media object class: {}", mediaObj.getClass().getName());
                                    logAllMethods(mediaObj);
                                    
                                    Object encryptQueryParam = getItemField(mediaObj, "getEncrypt_query_param", "getEncryptQueryParam", "getQueryParam");
                                    Object aesKey = getItemField(mediaObj, "getAes_key", "getAesKey", "getKey");

                                    if (encryptQueryParam instanceof String && aesKey instanceof String) {
                                        logger.info("Downloading encrypted file using ILinkClient.downloadMedia");
                                        Object cdnMedia = createCDNMedia(encryptQueryParam.toString(), aesKey.toString());
                                        Method downloadMediaMethod = client.getClass().getMethod("downloadMedia",
                                            Class.forName("com.github.wechat.ilink.sdk.core.model.CDNMedia"));
                                        Object result = downloadMediaMethod.invoke(client, cdnMedia);

                                        if (result instanceof byte[]) {
                                            byte[] fileBytes = (byte[]) result;
                                            logger.info("File downloaded successfully, size: {} bytes", fileBytes.length);
                                            return fileBytes;
                                        } else {
                                            logger.warn("Download result is not byte[]: {}", result != null ? result.getClass().getName() : "null");
                                        }
                                    } else {
                                        logger.warn("Encrypt query param or aes key is null: encryptQueryParam={}, aesKey={}", 
                                            encryptQueryParam, aesKey);
                                    }
                                } else {
                                    logger.warn("Media object is null");
                                    logger.info("Trying direct download from item...");
                                    try {
                                        Class<?> messageItemClass = Class.forName("com.github.wechat.ilink.sdk.core.model.MessageItem");
                                        Method downloadFileMethod = client.getClass().getMethod("downloadFileFromMessageItem", messageItemClass);
                                        Object result = downloadFileMethod.invoke(client, item);
                                        if (result instanceof byte[]) {
                                            byte[] fileBytes = (byte[]) result;
                                            logger.info("File downloaded via downloadFileFromMessageItem, size: {} bytes", fileBytes.length);
                                            return fileBytes;
                                        }
                                    } catch (Exception e) {
                                        logger.debug("downloadFileFromMessageItem not available: {}", e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Failed to download encrypted file", e);
                            }
                        } else {
                            logger.warn("File object is null, all methods failed");
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to process file item", e);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract file content", e);
        }
        return null;
    }

    private Object getItemField(Object obj, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = obj.getClass().getMethod(methodName);
                Object result = method.invoke(obj);
                if (result != null) {
                    logger.info("Got field via {}()", methodName);
                    return result;
                }
            } catch (Exception e) {
                logger.debug("{}() failed: {}", methodName, e.getMessage());
            }
        }
        return null;
    }

    private void logAllMethods(Object obj) {
        if (obj == null) return;
        Method[] methods = obj.getClass().getMethods();
        StringBuilder sb = new StringBuilder("Available methods: ");
        for (Method m : methods) {
            if (m.getName().startsWith("get") || m.getName().startsWith("is")) {
                sb.append(m.getName()).append("(), ");
            }
        }
        logger.info(sb.toString());
    }

    private String extractFileName(Object msg) {
        try {
            Method getItemListMethod = msg.getClass().getMethod("getItem_list");
            Object itemList = getItemListMethod.invoke(msg);

            if (!(itemList instanceof List<?>)) {
                return null;
            }

            List<?> items = (List<?>) itemList;
            for (Object item : items) {
                try {
                    Method getTypeMethod = item.getClass().getMethod("getType");
                    Object typeObj = getTypeMethod.invoke(item);
                    if (typeObj instanceof Integer && (Integer) typeObj == 4) {
                        Object fileObj = getItemField(item, "getFile_item", "getItem", "getFileItem", "getAppMsgItem", "getAttachItem");

                        if (fileObj != null) {
                            Object nameObj = getItemField(fileObj, "getFile_name", "getName", "getFileName", "getOriginalName");
                            if (nameObj instanceof String) {
                                logger.info("Extracted file name: {}", nameObj);
                                return (String) nameObj;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to process file item for name", e);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract file name", e);
        }
        return "unknown.bin";
    }

    private Object createCDNMedia(String encryptQueryParam, String aesKey) throws Exception {
        Class<?> cdnMediaClass = Class.forName("com.github.wechat.ilink.sdk.core.model.CDNMedia");
        Object cdnMedia = cdnMediaClass.getDeclaredConstructor().newInstance();
        
        try {
            Method setEncryptQueryParamMethod = cdnMediaClass.getMethod("setEncrypt_query_param", String.class);
            setEncryptQueryParamMethod.invoke(cdnMedia, encryptQueryParam);
            
            Method setAesKeyMethod = cdnMediaClass.getMethod("setAes_key", String.class);
            setAesKeyMethod.invoke(cdnMedia, aesKey);
        } catch (Exception e) {
            logger.warn("Failed to set CDNMedia fields using setter methods", e);
        }
        
        return cdnMedia;
    }

    private void sendReplyWithTtsFallback(String userId, String text) {
        try {
            logger.info("Attempting TTS synthesis for text: {}", text.length() > 50 ? text.substring(0, 50) + "..." : text);
            
            byte[] mp3Data = xfTtsService.synthesizeToMp3(text);
            logger.info("TTS synthesis successful, audio size: {} bytes", mp3Data.length);
            
            String fileName = "voice_" + System.currentTimeMillis() + ".mp3";
            
            try {
                java.lang.reflect.Method sendVoiceMethod = client.getClass().getMethod("sendVoice", String.class, byte[].class);
                sendVoiceMethod.invoke(client, userId, mp3Data);
                logger.info("Voice message sent successfully via sendVoice");
                return;
            } catch (NoSuchMethodException e) {
                logger.debug("sendVoice method not found, trying sendFile");
            }
            
            try {
                java.lang.reflect.Method sendVoiceMethod2 = client.getClass().getMethod("sendVoice", String.class, byte[].class, String.class);
                sendVoiceMethod2.invoke(client, userId, mp3Data, fileName);
                logger.info("Voice message sent successfully via sendVoice with fileName");
                return;
            } catch (NoSuchMethodException e) {
                logger.debug("sendVoice with 3 params not found, trying sendFile");
            }
            
            logger.info("Sending file: userId={}, fileName={}, size={}", userId, fileName, mp3Data.length);
            client.sendFile(userId, mp3Data, fileName, "audio/mp3");
            logger.info("Audio file sent successfully");
            return;
        } catch (Exception e) {
            logger.error("TTS synthesis or audio file sending failed, falling back to text", e);
        }
        
        try {
            client.sendText(userId, text);
            logger.info("Fallback: text message sent");
        } catch (Exception e) {
            logger.error("Failed to send fallback text message", e);
        }
    }

    private int estimateVoiceDuration(int mp3Length) {
        int estimatedBitrateKbps = 32;
        int bytesPerSecond = estimatedBitrateKbps * 1024 / 8;
        int durationSeconds = mp3Length / bytesPerSecond;
        return Math.max(1, durationSeconds);
    }

    private byte[] downloadImage(String imageUrl) throws Exception {
        if (imageUrl == null || imageUrl.isEmpty()) {
            throw new IllegalArgumentException("Image URL is null or empty");
        }

        String pathToCheck = imageUrl;
        if (pathToCheck.startsWith("file:///")) {
            pathToCheck = pathToCheck.substring(8);
        }

        java.nio.file.Path localPath = java.nio.file.Paths.get(pathToCheck);
        if (java.nio.file.Files.exists(localPath)) {
            logger.info("Reading image from local path: {}", localPath);
            return java.nio.file.Files.readAllBytes(localPath);
        }

        java.net.URL url = new java.net.URL(imageUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (java.io.InputStream is = conn.getInputStream()) {
            return is.readAllBytes();
        }
    }
}
