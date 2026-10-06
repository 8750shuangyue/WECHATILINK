package com.example.demo.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

@Component
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String NETWORK_ERROR_MSG = "由于网络原因，我处理不过来了，请稍后再试。";
    private static final String TOOL_ERROR_MSG = "工具执行失败，请重试。";
    private static final String UNKNOWN_ERROR_MSG = "处理请求时发生错误，请稍后再试。";

    public String handleException(Exception e) {
        logger.error("Global exception handler caught exception", e);
        
        if (e instanceof TimeoutException) {
            return NETWORK_ERROR_MSG;
        }
        
        if (e instanceof IOException) {
            String message = e.getMessage();
            if (message != null && (message.contains("timeout") || message.contains("Connection refused"))) {
                return NETWORK_ERROR_MSG;
            }
            return TOOL_ERROR_MSG;
        }
        
        if (e instanceof RuntimeException) {
            String message = e.getMessage();
            if (message != null && message.contains("解析失败")) {
                return "文件解析失败，可能是格式不支持或文件损坏。";
            }
            if (message != null && message.contains("超过限制")) {
                return message;
            }
            return TOOL_ERROR_MSG;
        }
        
        return UNKNOWN_ERROR_MSG;
    }

    public String handleToolExecutionException(String toolName, Exception e) {
        logger.error("Tool execution exception for tool: {}", toolName, e);
        
        return "工具调用失败，请重试。";
    }

    public String handleJsonParseException(String input) {
        logger.warn("Failed to parse JSON from LLM response: {}", input);
        return "处理响应时发生错误，请重试。";
    }

    public boolean shouldRetry(Exception e) {
        if (e instanceof IOException) {
            String message = e.getMessage();
            return message != null && (message.contains("timeout") || message.contains("Connection"));
        }
        return false;
    }
}