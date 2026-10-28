package com.example.demo.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 校验 JSON 请求里内联的 base64 图片（/api/care/identify 等）。
 * 先按编码长度预检，避免超大 payload 在解码阶段分配内存。
 */
@Component
public class InlineImageValidator {

    private final FileUploadValidator fileUploadValidator;
    private final long maxBytes;

    public InlineImageValidator(FileUploadValidator fileUploadValidator,
                                @Value("${app.upload.max-inline-image-bytes:6291456}") long maxBytes) {
        this.fileUploadValidator = fileUploadValidator;
        this.maxBytes = Math.max(1, maxBytes);
    }

    public record ValidatedImage(byte[] bytes, String mimeType) {
    }

    public byte[] decodeAndValidate(String input) {
        return decodeAndValidateWithType(input).bytes();
    }

    public ValidatedImage decodeAndValidateWithType(String input) {
        if (input == null || input.isBlank()) {
            throw new FileValidationException("图片内容不能为空");
        }

        String payload = input.trim();
        if (payload.startsWith("data:")) {
            int comma = payload.indexOf(',');
            if (comma < 0 || !payload.substring(0, comma).contains(";base64")) {
                throw new FileValidationException("图片 data URL 格式无效");
            }
            payload = payload.substring(comma + 1);
        }
        payload = payload.replaceAll("\\s", "");
        if (payload.isEmpty()) {
            throw new FileValidationException("图片内容不能为空");
        }

        long estimatedBytes = (long) payload.length() * 3 / 4;
        if (estimatedBytes > maxBytes) {
            throw new FileValidationException("图片不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new FileValidationException("图片 base64 编码无效", e);
        }
        if (decoded.length > maxBytes) {
            throw new FileValidationException("图片不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }

        String extension = fileUploadValidator.validateImageBytes(decoded);
        return new ValidatedImage(decoded, mimeTypeOf(extension));
    }

    private String mimeTypeOf(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tiff" -> "image/tiff";
            default -> "image/jpeg";
        };
    }
}
