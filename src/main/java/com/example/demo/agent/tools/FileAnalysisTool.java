package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.LlmService;
import com.example.demo.core.FileParserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
public class FileAnalysisTool extends BaseTool {

    public static final String TOOL_NAME = "analyzeFile";
    public static final String TOOL_DESCRIPTION = "分析用户上传的文件内容，支持txt、md、json、csv、log、pdf、docx格式。当用户上传文件后询问文件内容、要求总结、提取信息、翻译文件或分析数据时必须调用此工具。如果文件内容过大，会自动生成摘要。严禁用自身训练数据回答文件内容。";

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_TOKENS_FOR_DIRECT_USE = 4000;
    private static final int ESTIMATED_CHARS_PER_TOKEN = 2;

    private final FileParserService fileParserService;
    private final ToolDefinition toolDefinition;
    
    @Autowired
    @Lazy
    private LlmService llmService;

    @Value("${server.port:8080}")
    private String serverPort;

    public FileAnalysisTool(FileParserService fileParserService) {
        this.fileParserService = fileParserService;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("fileUrl", "string", "文件的访问URL，可以是本地路径(file:///开头)或远程HTTP URL")
                .parameter("fileName", "string", "文件名，包含扩展名，用于判断文件类型")
                .parameter("userQuery", "string", "用户针对文件内容的具体问题，可选")
                .required("fileUrl")
                .required("fileName")
                .build();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public ToolDefinition getDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolResult<Map<String, Object>> execute(JSONObject params) {
        String fileUrl = params.getString("fileUrl");
        String fileName = params.getString("fileName");
        String userQuery = params.getString("userQuery");

        if (fileUrl == null || fileUrl.trim().isEmpty()) {
            return ToolResult.failure("请提供文件URL");
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            return ToolResult.failure("请提供文件名");
        }

        logger.info("Executing file analysis tool, fileUrl: {}, fileName: {}", fileUrl, fileName);

        try {
            byte[] fileContent = downloadFile(fileUrl);
            
            if (fileContent.length > MAX_FILE_SIZE) {
                return ToolResult.failure("文件大小超过限制（最大5MB）");
            }

            String textContent = fileParserService.parseBytes(fileContent, fileName);
            logger.info("File parsed successfully, content length: {} chars", textContent.length());

            int estimatedTokens = textContent.length() / ESTIMATED_CHARS_PER_TOKEN;
            boolean fullTextAvailable = estimatedTokens <= MAX_TOKENS_FOR_DIRECT_USE;

            String resultText;
            if (fullTextAvailable) {
                resultText = textContent;
            } else {
                logger.info("Content exceeds {} tokens, generating summary", MAX_TOKENS_FOR_DIRECT_USE);
                resultText = generateSummary(textContent, userQuery);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("summary", resultText);
            result.put("full_text_available", fullTextAvailable);
            result.put("estimated_tokens", estimatedTokens);
            result.put("file_name", fileName);

            if (userQuery != null && !userQuery.isEmpty()) {
                result.put("user_query", userQuery);
            }

            return ToolResult.success(result);

        } catch (Exception e) {
            logger.error("File analysis tool execution failed", e);
            return ToolResult.failure("文件解析失败，可能是格式不支持或文件损坏");
        }
    }

    private byte[] downloadFile(String fileUrl) throws IOException {
        if (fileUrl.startsWith("file:///")) {
            String localPath = fileUrl.substring(8);
            return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(localPath));
        }

        if (fileUrl.startsWith("http://") || fileUrl.startsWith("https://")) {
            URL url = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            try (java.io.InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        }

        if (fileUrl.startsWith("data:")) {
            String base64Data = fileUrl.split(",")[1];
            return Base64.getDecoder().decode(base64Data);
        }

        return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(fileUrl));
    }

    private String generateSummary(String textContent, String userQuery) {
        try {
            String summaryPrompt = """
                请对以下文件内容生成一个约500字的中文摘要。
                重点提取文件的核心内容、关键数据和主要结论。
                
                %s
                
                文件内容：
                %s
                """.formatted(
                    userQuery != null ? "用户问题：" + userQuery : "",
                    truncateForSummary(textContent)
                );

            String summary = llmService.chat(summaryPrompt);
            logger.info("Summary generated, length: {} chars", summary.length());
            return summary;

        } catch (Exception e) {
            logger.error("Failed to generate summary", e);
            return "文件内容过长，无法生成摘要。文件包含约 " + textContent.length() + " 个字符。";
        }
    }

    private String truncateForSummary(String text) {
        int maxLength = 15000;
        if (text.length() <= maxLength) {
            return text;
        }
        
        int firstPart = maxLength / 2;
        int lastPart = maxLength / 2;
        
        return text.substring(0, firstPart) + "\n\n[...内容省略...]\n\n" + 
               text.substring(text.length() - lastPart);
    }
}