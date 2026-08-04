package com.example.demo.ai;

import com.alibaba.fastjson2.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResult {

    private String traceId;

    private String toolName;

    private JSONObject arguments;

    private String result;

    private boolean success;

    private long durationMs;

    private LocalDateTime timestamp;

    private String errorMessage;

    public static ToolCallResult success(String traceId, String toolName,
                                          JSONObject arguments, String result, long durationMs) {
        return ToolCallResult.builder()
                .traceId(traceId)
                .toolName(toolName)
                .arguments(arguments)
                .result(result)
                .success(true)
                .durationMs(durationMs)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ToolCallResult error(String traceId, String toolName,
                                        JSONObject arguments, String errorMessage, long durationMs) {
        return ToolCallResult.builder()
                .traceId(traceId)
                .toolName(toolName)
                .arguments(arguments)
                .result("工具执行异常：" + errorMessage)
                .success(false)
                .durationMs(durationMs)
                .timestamp(LocalDateTime.now())
                .errorMessage(errorMessage)
                .build();
    }
}
