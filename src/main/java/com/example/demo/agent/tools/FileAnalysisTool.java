package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.LlmService;
import com.example.demo.core.FileParserService;
import com.example.demo.core.UploadPathProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Component
public class FileAnalysisTool extends BaseTool {

    public static final String TOOL_NAME = "analyzeFile";
    public static final String TOOL_DESCRIPTION = "分析用户上传的文件内容，支持txt、md、json、csv、log、pdf、docx格式。当用户上传文件后询问文件内容、要求总结、提取信息、翻译文件或分析数据时必须调用此工具。如果文件内容过大，会自动生成摘要。严禁用自身训练数据回答文件内容。";

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_TOKENS_FOR_DIRECT_USE = 4000;
    private static final int ESTIMATED_CHARS_PER_TOKEN = 2;
    private static final String UPLOADS_PREFIX = "/uploads/";

    private final FileParserService fileParserService;
    private final UploadPathProvider uploadPathProvider;
    private final ToolDefinition toolDefinition;

    @Autowired
    @Lazy
    private LlmService llmService;

    @Value("${server.port:8080}")
    private String serverPort;

    public FileAnalysisTool(FileParserService fileParserService,
                            UploadPathProvider uploadPathProvider) {
        this.fileParserService = fileParserService;
        this.uploadPathProvider = uploadPathProvider;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("fileUrl", "string", "文件地址：本系统上传文件的 /uploads/ 路径，或公网 HTTP(S) URL")
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

    /**
     * 只允许两类来源：本系统 uploads 目录下的文件，以及公网 HTTP(S) 地址。
     * 拒绝 data:、任意绝对路径、file:/// 任意位置和内网/回环地址，避免 LFI/SSRF。
     */
    private byte[] downloadFile(String fileUrl) throws IOException {
        String value = fileUrl == null ? "" : fileUrl.trim();

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return downloadHttp(value);
        }

        if (value.startsWith("file:///")) {
            Path path = Paths.get(URI.create(value));
            return readUploadsFile(path);
        }

        String relative = value;
        if (relative.startsWith(UPLOADS_PREFIX)) {
            relative = relative.substring(UPLOADS_PREFIX.length());
        } else if (relative.startsWith("uploads/")) {
            relative = relative.substring("uploads/".length());
        } else {
            throw new IOException("只允许读取 uploads 目录下的文件或公网 HTTP(S) 地址");
        }

        if (relative.contains("?") || relative.contains("#")) {
            relative = relative.replaceAll("[?#].*$", "");
        }
        return readUploadsFile(uploadPathProvider.resolve(relative));
    }

    private byte[] readUploadsFile(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!uploadPathProvider.isWithinRoot(normalized)) {
            throw new IOException("只允许读取 uploads 目录下的文件");
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("文件不存在");
        }
        long size = Files.size(normalized);
        if (size > MAX_FILE_SIZE) {
            throw new IOException("文件大小超过限制（最大5MB）");
        }
        return Files.readAllBytes(normalized);
    }

    private byte[] downloadHttp(String value) throws IOException {
        URL url = new URL(value);
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("仅支持 HTTP(S) 地址");
        }
        assertPublicHost(url);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);

        int status = conn.getResponseCode();
        if (status >= 300 && status < 400) {
            throw new IOException("不允许跳转下载");
        }
        if (status != 200) {
            throw new IOException("下载失败，HTTP 状态码: " + status);
        }
        long contentLength = conn.getContentLengthLong();
        if (contentLength > MAX_FILE_SIZE) {
            throw new IOException("文件大小超过限制（最大5MB）");
        }

        try (InputStream is = conn.getInputStream()) {
            return readLimited(is, MAX_FILE_SIZE);
        } finally {
            conn.disconnect();
        }
    }

    private void assertPublicHost(URL url) throws IOException {
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL 缺少主机名");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new IOException("无法解析主机名: " + host, e);
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IOException("不允许访问内网或本机地址");
            }
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 16) {
            // IPv6 唯一本地地址 fc00::/7
            if ((raw[0] & 0xFE) == 0xFC) {
                return false;
            }
            // IPv4 映射地址 ::ffff:a.b.c.d
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                return isPublicIpv4(raw, 12);
            }
            return true;
        }
        return isPublicIpv4(raw, 0);
    }

    private boolean isPublicIpv4(byte[] raw, int offset) {
        int a = raw[offset] & 0xFF;
        int b = raw[offset + 1] & 0xFF;
        // 100.64.0.0/10 CGNAT
        if (a == 100 && b >= 64 && b <= 127) {
            return false;
        }
        // 0.0.0.0/8
        if (a == 0) {
            return false;
        }
        // 169.254.0.0/16 链路本地（含云厂商元数据 169.254.169.254）
        if (a == 169 && b == 254) {
            return false;
        }
        // 127.0.0.0/8
        if (a == 127) {
            return false;
        }
        return true;
    }

    private byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("文件大小超过限制（最大5MB）");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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