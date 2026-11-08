package com.example.demo.chat;

import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 向量文档的稳定 ID、序列化与 SHA-256 工具，写入侧与治理侧共用。 */
@Slf4j
final class VectorDocuments {

    private VectorDocuments() {
    }

    static String conversationKey(String userId, String conversationId) {
        StringBuilder key = new StringBuilder();
        appendLengthPrefixed(key, userId);
        appendLengthPrefixed(key, conversationId);
        return key.toString();
    }

    static String messageDocumentId(String userId, String conversationId,
                                    String userMessage, String assistantReply) {
        StringBuilder identity = new StringBuilder();
        appendLengthPrefixed(identity, userId);
        appendLengthPrefixed(identity, conversationId);
        appendLengthPrefixed(identity, userMessage);
        appendLengthPrefixed(identity, assistantReply);
        return sha256(identity.toString());
    }

    static String chunkDocumentId(String sourceId, int chunkIndex, String content) {
        StringBuilder identity = new StringBuilder();
        identity.append("public_kb:");
        appendLengthPrefixed(identity, sourceId);
        appendLengthPrefixed(identity, String.valueOf(chunkIndex));
        appendLengthPrefixed(identity, content);
        return sha256(identity.toString());
    }

    static void appendLengthPrefixed(StringBuilder builder, String value) {
        String normalized = value == null ? "" : value;
        builder.append(normalized.length()).append(':').append(normalized);
    }

    static byte[] serializeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    static String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            log.warn("[RAG] Failed to calculate SHA-256 hash: {}", e.getMessage());
            return null;
        }
    }
}
