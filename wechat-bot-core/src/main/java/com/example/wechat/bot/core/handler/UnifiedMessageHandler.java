package com.example.wechat.bot.core.handler;

import com.example.wechat.bot.core.model.dto.MessageResponse;
import com.example.wechat.bot.core.model.dto.FileContent;
import com.example.wechat.bot.core.model.dto.UnifiedContext;
import com.example.wechat.bot.core.service.AudioGenerationService;
import com.example.wechat.bot.core.service.ChatCompletionService;
import com.example.wechat.bot.core.service.ConversationMemoryService;
import com.example.wechat.bot.core.service.ImageGenerationService;
import com.example.wechat.bot.core.service.VisionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.example.wechat.bot.common.util.StringUtil;

/**
 * 统一消息处理器。
 * <p>
 * 分两类：
 * <ol>
 *   <li><b>纯非文本</b>（有图片或文件，但没有任何文字）→ 识别图片并存入记忆（仅图片），直接回复"需要我做什么？"，不调 AI</li>
 *   <li><b>纯文本</b> → 直接走结构化对话 + formats 路由 + 执行</li>
 * </ol>
 */
@Component
public class UnifiedMessageHandler {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(UnifiedMessageHandler.class);

    // ========== System Prompts ==========

    // 统一结构化对话 + 格式路由的 system prompt
    private static final String STRUCTURED_SYSTEM_PROMPT =
            "你是一个简洁友好的微信聊天助手。请根据用户输入生成回复。\n"
            + "你必须按 JSON 格式回复，格式如下：\n"
            + "{\"reply\": \"回复的内容\", \"formats\": [\"text\"]}\n"
            + "- 如果用户要求朗读或语音回复，将 formats 设置为 [\"voice\"]\n"
            + "- 如果用户要求画图、生成图片、创作图像等，将 formats 设置为 [\"image\"]，reply 字段填你优化后的图片描述（可以直接作为图片生成模型的 prompt）\n"
            + "- 如果用户同时有多个要求，将 formats 设置为对应的多个值，例如 [\"image\", \"voice\"]，reply 为你想发出的文字\n"
            + "- 否则始终为 [\"text\"]\n"
            + "请严格按 JSON 格式输出，不要包含其他说明文字。";

    // 图片识别 prompt -- 用户没有具体指令时用通用描述
    private static final String DESCRIBE_GENERIC_PROMPT =
            "请详细描述这张图片的场景、主体、颜色、文字等所有可见信息。";

    private final ChatCompletionService chatCompletionService;
    private final VisionService visionService;
    private final ImageGenerationService imageGenerationService;
    private final AudioGenerationService audioGenerationService;
    private final ConversationMemoryService conversationMemoryService;

    public UnifiedMessageHandler(ChatCompletionService chatCompletionService,
                                  VisionService visionService,
                                  ImageGenerationService imageGenerationService,
                                  AudioGenerationService audioGenerationService,
                                  ConversationMemoryService conversationMemoryService) {
        this.chatCompletionService = chatCompletionService;
        this.visionService = visionService;
        this.imageGenerationService = imageGenerationService;
        this.audioGenerationService = audioGenerationService;
        this.conversationMemoryService = conversationMemoryService;
    }

    // ========== 入口 ==========

    /**
     * 处理统一上下文，返回响应列表。
     */
    public List<MessageResponse> handle(UnifiedContext ctx) {
        if (ctx.userId() == null) {
            return List.of();
        }

        // 纯非文本 → 直接回复，不调 AI
        if (ctx.isPureNonText()) {
            if (ctx.hasImage()) {
                String imageDesc = describeImage(ctx.imageBytes());
                if (imageDesc != null) {
                    String descLine = "（用户发送了一张图片，内容为：" + imageDesc + "）";
                    conversationMemoryService.addAssistantMessage(ctx.userId(), descLine);
                }
            }
            return List.of(MessageResponse.text("你发了" + ctx.mediaDescription() + "，需要我做什么？"));
        }

        // 纯文本 → 直接走结构化对话
        String json = chatCompletionService.structuredChat(
                ctx.userId(), ctx.text(), STRUCTURED_SYSTEM_PROMPT);
        return parseAndRouteFormats(ctx.userId(), json);
    }

    // ========== 图片识别 ==========

    /**
     * 调多模态 API 识别图片，返回文字描述。
     *
     * @param imageBytes 图片字节
     * @return 图片文字描述，识别失败返回 null
     */
    private String describeImage(byte[] imageBytes) {
        String result = visionService.analyze(imageBytes, DESCRIBE_GENERIC_PROMPT);

        if (result == null || result.isBlank()) {
            log.warn("图片识别返回空");
            return null;
        }

        // 检查是否是 API 错误消息
        if (result.startsWith("图片识别失败") || result.startsWith("无法识别")
                || result.startsWith("视觉识别服务未配置") || result.startsWith("调用视觉 API 失败")) {
            log.warn("图片识别失败: {}", StringUtil.truncate(result, 100));
            return null;
        }

        return result;
    }
    /**
     * 提取文件内容并格式化为上下文文本
     *
     * @return 格式化后的文件上下文（不含用户文字），提取失败返回 null
     */
    private String extractAsFileContext(FileContent fileContent) {
        String fileName = fileContent.fileName();
        String ext = getFileExtension(fileName);
        if (ext == null) return null;

        String extractedText = extractTextFromFile(fileContent.bytes(), ext, fileName);
        if (extractedText == null || extractedText.isBlank()) return null;

        if (extractedText.length() > 15000) {
            log.info("文件内容过长 ({} 字符)，截取前 15000 字符", extractedText.length());
            extractedText = extractedText.substring(0, 15000) + "\n\n...（文件较长，已截取前 15000 字符）";
        }


        return "用户发送了一个文件【" + StringUtil.sanitize(fileName) + "】，大小：" + StringUtil.formatFileSize(fileContent.fileSize())
                + "\n文件内容：\n---\n" + extractedText + "\n---";
    }

    private static final Set<String> SUPPORTED_TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "md", "json", "xml", "csv", "log",
            "properties", "yaml", "yml", "toml", "ini", "cfg",
            "java", "py", "js", "ts", "go", "rs", "rb",
            "html", "css", "scss", "less", "sh", "bash",
            "sql", "gradle", "kt", "prototxt", "cfg"
    ));

    private static final Set<String> TIKA_SUPPORTED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "pdf", "docx", "pptx", "xlsx", "doc", "ppt", "xls", "odt", "ods", "odp", "rtf"
    ));

    /**
     * 根据文件类型提取文本内容
     */
    private static String extractTextFromFile(byte[] bytes, String ext, String fileName) {
        try {
            if (SUPPORTED_TEXT_EXTENSIONS.contains(ext)) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (TIKA_SUPPORTED_EXTENSIONS.contains(ext)) {
                try {
                    InputStream is = new ByteArrayInputStream(bytes);
                    Class<?> tikaClass = Class.forName("org.apache.tika.Tika");
                    Object tika = tikaClass.getDeclaredConstructor().newInstance();
                    return (String) tikaClass.getMethod("parseToString", InputStream.class).invoke(tika, is);
                } catch (ClassNotFoundException e) {
                    log.warn("Tika 未在 classpath 中找到，无法解析文档格式: .{}", ext);
                    return null;
                } catch (Exception e) {
                    log.warn("Tika 解析文档失败 fileName={}: {}", StringUtil.sanitize(fileName), e.getMessage());
                    return null;
                }
            }
            log.debug("不支持的文档格式: .{}", ext);
            return null;
        } catch (Exception e) {
            log.warn("提取文件文本失败 fileName={}: {}", StringUtil.sanitize(fileName), e.getMessage());
            return null;
        }
    }

    private static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase().trim();
    }

    // ========== 格式路由 ==========

    /**
     * 解析 AI 返回的 JSON {reply, formats} 并路由执行
     */
    private List<MessageResponse> parseAndRouteFormats(String userId, String json) {
        if (json == null) {
            log.warn("AI 结构化对话返回 null，降级为简单文本回复");
            return List.of(MessageResponse.text("收到，请说点什么吧"));
        }

        try {
            JsonNode root = JSON_MAPPER.readTree(StringUtil.extractJsonFromResponse(json));
            String reply = root.has("reply") ? root.get("reply").asText() : null;

            if (reply == null || reply.isBlank()) {
                log.warn("AI 返回的 JSON 中 reply 为空，降级为简单文本回复");
                return List.of(MessageResponse.text("收到，请说点什么吧"));
            }

            // 解析 formats 数组
            List<String> formats = new ArrayList<>();
            JsonNode formatsNode = root.get("formats");
            if (formatsNode != null && formatsNode.isArray()) {
                for (JsonNode f : formatsNode) {
                    String fmt = f.asText();
                    if ("text".equals(fmt) || "voice".equals(fmt) || "image".equals(fmt)) {
                        formats.add(fmt);
                    }
                }
            } else if (root.has("format")) {
                formats.add(root.get("format").asText());
            }
            if (formats.isEmpty()) {
                formats.add("text");
            }

            List<MessageResponse> responses = new ArrayList<>();

            // 仅当唯一的格式不是 text 时，才跳过先发文字
            boolean addTextFirst = !(formats.size() == 1 && !"text".equals(formats.get(0)));
            if (addTextFirst) {
                responses.add(MessageResponse.text(reply));
            }

            // 依次处理每个格式
            for (String format : formats) {
                switch (format) {
                    case "voice" -> {
                        log.info("AI 决策为语音回复, reply={}", StringUtil.truncate(reply, 50));
                        CompletableFuture<MessageResponse> future = CompletableFuture.supplyAsync(() -> {
                            byte[] audioBytes = audioGenerationService.generate(reply);
                            if (audioBytes != null) {
                                return MessageResponse.voice(audioBytes, null);
                            }
                            log.warn("TTS 生成失败，跳过语音");
                            return MessageResponse.text(reply);
                        });
                        responses.add(MessageResponse.deferred("正在为您生成语音，请稍候…", future));
                    }
                    case "image" -> {
                        log.info("AI 决策为图片生成, prompt={}", StringUtil.truncate(reply, 80));
                        CompletableFuture<MessageResponse> future = CompletableFuture.supplyAsync(() -> {
                            byte[] bytes = imageGenerationService.generate(reply);
                            if (bytes != null) {
                                return MessageResponse.image(bytes, "generated_image.png");
                            }
                            log.warn("图片生成失败");
                            return MessageResponse.text("图片生成失败，请稍后重试。");
                        });
                        responses.add(MessageResponse.deferred("正在为您生成图片，请稍候…", future));
                    }
                }
            }

            return responses;
        } catch (JsonProcessingException e) {
            log.warn("AI 返回的 JSON 解析失败, raw={}, 降级为文本回复", json, e);
            return List.of(MessageResponse.text("收到，请说点什么吧"));
        }
    }

}
