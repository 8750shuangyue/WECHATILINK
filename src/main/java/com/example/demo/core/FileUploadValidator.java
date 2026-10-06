package com.example.demo.core;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class FileUploadValidator {

    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("txt", "md", "json", "csv", "log");
    private static final Set<String> PDF_EXTENSIONS = Set.of("pdf");
    private static final Set<String> DOCX_EXTENSIONS = Set.of("docx");
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff");
    private static final Set<String> AUDIO_EXTENSIONS =
            Set.of("wav", "webm", "ogg", "oga", "opus", "mp3", "m4a", "mp4", "aac", "flac");

    private static final int MAX_DOCX_ENTRIES = 4096;
    private static final long MAX_DOCX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;

    public String validateText(MultipartFile file) {
        String extension = validateBasicAndExtension(file);
        if (!TEXT_EXTENSIONS.contains(extension)) {
            throw new FileValidationException("仅支持文本文件：" + TEXT_EXTENSIONS);
        }
        validateTextContent(readBytes(file));
        return extension;
    }

    public String validateImage(MultipartFile file) {
        String extension = validateBasicAndExtension(file);
        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw new FileValidationException("仅支持图片文件：" + IMAGE_EXTENSIONS);
        }
        return validateImageBytes(readBytes(file), extension);
    }

    public String validateDocument(MultipartFile file) {
        String extension = validateBasicAndExtension(file);
        byte[] content = readBytes(file);

        if (TEXT_EXTENSIONS.contains(extension)) {
            validateTextContent(content);
            return extension;
        }
        if (PDF_EXTENSIONS.contains(extension)) {
            validatePdf(content);
            return extension;
        }
        if (DOCX_EXTENSIONS.contains(extension)) {
            validateDocx(content);
            return extension;
        }
        throw new FileValidationException("不支持的文件类型，仅支持：txt、md、json、csv、log、pdf、docx");
    }

    public String validateAudio(MultipartFile file) {
        String extension = validateBasicAndExtension(file);
        if (!AUDIO_EXTENSIONS.contains(extension)) {
            throw new FileValidationException("仅支持音频文件：" + AUDIO_EXTENSIONS);
        }

        String detected = detectAudioExtension(readBytes(file));
        if (detected == null || !isCompatibleAudioExtension(extension, detected)) {
            throw new FileValidationException("音频内容与扩展名不匹配或格式不受支持");
        }
        return detected;
    }

    public String canonicalImageExtension(byte[] imageBytes, String extensionOrFilename) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new FileValidationException("图片文件不能为空");
        }
        if (imageBytes.length > MAX_UPLOAD_BYTES) {
            throw new FileValidationException("文件不能超过 10MB");
        }
        String extension = extensionOf(extensionOrFilename);
        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw new FileValidationException("仅支持图片文件：" + IMAGE_EXTENSIONS);
        }
        return validateImageBytes(imageBytes, extension);
    }

    private String validateBasicAndExtension(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new FileValidationException("文件不能超过 10MB");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new FileValidationException("文件名不能为空");
        }
        String extension = extensionOf(filename);
        if (extension.isEmpty()) {
            throw new FileValidationException("文件缺少扩展名");
        }
        return extension;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new FileValidationException("无法读取上传文件", e);
        }
    }

    private void validateTextContent(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                throw new FileValidationException("文本文件包含非法 NUL 字节");
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw new FileValidationException("文本文件必须使用 UTF-8 编码", e);
        }
    }

    private void validatePdf(byte[] content) {
        int limit = Math.min(content.length - 4, 1024);
        for (int i = 0; i < limit; i++) {
            if (content[i] == '%' && content[i + 1] == 'P'
                    && content[i + 2] == 'D' && content[i + 3] == 'F'
                    && content[i + 4] == '-') {
                return;
            }
        }
        throw new FileValidationException("PDF 文件头无效");
    }

    private void validateDocx(byte[] content) {
        if (!startsWith(content, new byte[]{0x50, 0x4B, 0x03, 0x04})
                && !startsWith(content, new byte[]{0x50, 0x4B, 0x05, 0x06})
                && !startsWith(content, new byte[]{0x50, 0x4B, 0x07, 0x08})) {
            throw new FileValidationException("DOCX 文件头无效");
        }

        boolean hasContentTypes = false;
        boolean hasDocumentXml = false;
        int entries = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_DOCX_ENTRIES) {
                    throw new FileValidationException("DOCX 文件条目过多");
                }

                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    throw new FileValidationException("DOCX 包含非法路径");
                }
                if ("[Content_Types].xml".equals(name)) {
                    hasContentTypes = true;
                }
                if ("word/document.xml".equals(name)) {
                    hasDocumentXml = true;
                }

                int read;
                while ((read = zip.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_DOCX_UNCOMPRESSED_BYTES) {
                        throw new FileValidationException("DOCX 解压后体积过大");
                    }
                }
            }
        } catch (IOException e) {
            throw new FileValidationException("DOCX 文件损坏", e);
        }

        if (!hasContentTypes || !hasDocumentXml) {
            throw new FileValidationException("DOCX 文件结构无效");
        }
    }

    private String validateImageBytes(byte[] content, String extension) {
        String detected = detectImageExtension(content);
        if (detected == null || !isCompatibleImageExtension(extension, detected)) {
            throw new FileValidationException("图片内容与扩展名不匹配或格式不受支持");
        }
        return detected;
    }

    private String detectImageExtension(byte[] content) {
        if (startsWith(content, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "jpg";
        }
        if (startsWith(content, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "png";
        }
        if (startsWith(content, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(content, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return "gif";
        }
        if (startsWith(content, new byte[]{'B', 'M'})) {
            return "bmp";
        }
        if (content.length >= 12
                && startsWith(content, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && matchesAt(content, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return "webp";
        }
        if (startsWith(content, new byte[]{'I', 'I', 0x2A, 0x00})
                || startsWith(content, new byte[]{'M', 'M', 0x00, 0x2A})) {
            return "tiff";
        }
        return null;
    }

    private String detectAudioExtension(byte[] content) {
        if (content.length >= 12
                && startsWith(content, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && matchesAt(content, 8, "WAVE".getBytes(StandardCharsets.US_ASCII))) {
            return "wav";
        }
        if (startsWith(content, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})) {
            return "webm";
        }
        if (startsWith(content, "OggS".getBytes(StandardCharsets.US_ASCII))) {
            return "ogg";
        }
        if (content.length >= 2
                && (content[0] & 0xFF) == 0xFF
                && ((content[1] & 0xF6) == 0xF0 || (content[1] & 0xF6) == 0xF8)) {
            return "aac";
        }
        if (startsWith(content, "ID3".getBytes(StandardCharsets.US_ASCII))
                || (content.length >= 2
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xE0) == 0xE0)) {
            return "mp3";
        }
        if (content.length >= 12
                && matchesAt(content, 4, "ftyp".getBytes(StandardCharsets.US_ASCII))) {
            return "mp4";
        }
        if (startsWith(content, "fLaC".getBytes(StandardCharsets.US_ASCII))) {
            return "flac";
        }
        return null;
    }

    private boolean isCompatibleImageExtension(String declared, String detected) {
        if ("jpeg".equals(declared)) {
            declared = "jpg";
        }
        if ("tif".equals(declared)) {
            declared = "tiff";
        }
        return declared.equals(detected);
    }

    private boolean isCompatibleAudioExtension(String declared, String detected) {
        if ("oga".equals(declared) || "opus".equals(declared)) {
            declared = "ogg";
        }
        if ("m4a".equals(declared)) {
            declared = "mp4";
        }
        return declared.equals(detected);
    }

    private String extensionOf(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        int dot = normalized.lastIndexOf('.');
        if (dot < 0) {
            return normalized.matches("[a-z0-9]+") ? normalized : "";
        }
        if (dot == normalized.length() - 1) {
            return "";
        }
        return normalized.substring(dot + 1).replaceAll("[^a-z0-9]", "");
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        return matchesAt(content, 0, prefix);
    }

    private boolean matchesAt(byte[] content, int offset, byte[] expected) {
        if (content == null || expected == null || offset < 0 || content.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (content[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
