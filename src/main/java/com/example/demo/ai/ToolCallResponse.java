package com.example.demo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResponse {

    private String text;

    @Builder.Default
    private List<Path> generatedFiles = new ArrayList<>();

    @Builder.Default
    private List<ToolCallResult> toolCallHistory = new ArrayList<>();

    private int totalIterations;

    private long totalTokens;

    private String traceId;

    public List<Path> imageFiles() {
        if (generatedFiles == null) return List.of();
        return generatedFiles.stream()
                .filter(p -> {
                    String s = p.toString().toLowerCase();
                    return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".gif");
                })
                .toList();
    }

    public List<Path> audioFiles() {
        if (generatedFiles == null) return List.of();
        return generatedFiles.stream()
                .filter(p -> {
                    String s = p.toString().toLowerCase();
                    return s.endsWith(".mp3") || s.endsWith(".wav") || s.endsWith(".ogg");
                })
                .toList();
    }

    public boolean hasGeneratedFiles() {
        return generatedFiles != null && !generatedFiles.isEmpty();
    }

    public boolean hasToolCalls() {
        return toolCallHistory != null && !toolCallHistory.isEmpty();
    }
}
