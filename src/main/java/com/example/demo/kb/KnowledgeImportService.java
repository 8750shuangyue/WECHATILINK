package com.example.demo.kb;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class KnowledgeImportService {

    public static final int CHUNK_LENGTH = 500;
    private static final int SOURCE_ID_MAX_LENGTH = 200;
    private static final Pattern SOURCE_ID_PATTERN =
            Pattern.compile("[\\p{L}\\p{N}._:-]+");

    public ImportPlan plan(String requestedSourceId, String originalFilename, String text) {
        String fileName = normalizeFileName(originalFilename);
        String normalizedText = normalizeText(text);
        if (normalizedText.isBlank()) {
            throw new KnowledgeImportException("文档解析后没有可用文本");
        }

        List<String> chunks = chunk(normalizedText);
        if (chunks.isEmpty()) {
            throw new KnowledgeImportException("文档解析后没有可入库片段");
        }

        return new ImportPlan(
                resolveSourceId(requestedSourceId, fileName),
                fileName,
                normalizedText.length(),
                chunks
        );
    }

    private String resolveSourceId(String requestedSourceId, String fileName) {
        if (requestedSourceId != null && !requestedSourceId.isBlank()) {
            String sourceId = requestedSourceId.trim();
            if (sourceId.length() > SOURCE_ID_MAX_LENGTH
                    || !SOURCE_ID_PATTERN.matcher(sourceId).matches()) {
                throw new KnowledgeImportException(
                        "sourceId 仅支持中文、字母、数字、点、下划线、冒号和短横线，且长度不能超过 200");
            }
            return sourceId;
        }

        String canonicalName = fileName.toLowerCase(Locale.ROOT);
        return "kb_" + sha256("public_kb|" + canonicalName).substring(0, 32);
    }

    private String normalizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "document";
        }
        String normalized = originalFilename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.trim();
        return normalized.isEmpty() ? "document" : normalized;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        String[] paragraphs = text.split("\\n+");
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (buffer.length() + trimmed.length() > CHUNK_LENGTH && buffer.length() > 0) {
                out.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            buffer.append(trimmed).append('\n');
        }
        if (buffer.length() > 0) {
            out.add(buffer.toString().trim());
        }
        return out;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算文档标识", e);
        }
    }

    public record ImportPlan(String sourceId, String fileName, int charCount, List<String> chunks) {
        public ImportPlan {
            chunks = List.copyOf(chunks);
        }
    }
}
