package com.example.demo.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationPromptTest {

    @Test
    void instructionsNameTheValidRange() {
        String instructions = CitationPrompt.instructions(3);

        assertTrue(instructions.contains("1 到 3"));
        assertTrue(instructions.contains("句子末尾"));
        assertTrue(instructions.contains("不要引用「相关历史对话」"));
    }

    @Test
    void noSourcesMeansExplicitlyForbidMarkers() {
        String instructions = CitationPrompt.instructions(0);

        assertTrue(instructions.contains("不要出现 [n]"));
        assertTrue(instructions.contains("不要编造来源"));
    }

    @Test
    void appendsToExistingRagContext() {
        String combined = CitationPrompt.appendTo("公共知识库信息 1:\n浇水知识", 1);

        assertTrue(combined.startsWith("公共知识库信息 1:\n浇水知识"));
        assertTrue(combined.contains("【引用要求】"));
        assertTrue(combined.contains("1 到 1"));
    }

    @Test
    void returnsInstructionsWhenContextIsBlank() {
        String combined = CitationPrompt.appendTo("  ", 0);

        assertTrue(combined.startsWith("【引用要求】"));
        assertTrue(combined.contains("不要出现 [n]"));
    }
}
