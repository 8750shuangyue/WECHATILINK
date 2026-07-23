package com.example.wechat.bot.core.model.dto;

/**
 * 用户发送的文件内容，包含文件名、字节流和大小。
 */
public record FileContent(String fileName, byte[] bytes, long fileSize) {
}
