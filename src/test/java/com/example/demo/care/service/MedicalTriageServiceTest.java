package com.example.demo.care.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalTriageServiceTest {

    @Test
    void parsesModelTextAsJsonInsteadOfFallingBackToMedium() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "{\"level\":\"HIGH\",\"reason\":\"需要观察\",\"advice\":\"尽快咨询兽医\"}"));

        MedicalTriageService service = new MedicalTriageService(chatModel);

        MedicalTriageService.TriageResult result = service.analyze("猫咪轻微掉毛", "PET");

        assertEquals(MedicalTriageService.TriageLevel.HIGH, result.getLevel());
        assertEquals("需要观察", result.getReason());
    }

    @Test
    void stripsMarkdownCodeFenceBeforeParsing() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "```json\n{\"level\":\"LOW\",\"reason\":\"正常\",\"advice\":\"保持观察\"}\n```"));

        MedicalTriageService service = new MedicalTriageService(chatModel);

        MedicalTriageService.TriageResult result = service.analyze("猫咪轻微掉毛", "PET");

        assertEquals(MedicalTriageService.TriageLevel.LOW, result.getLevel());
    }

    @Test
    void criticalSymptomShortCircuitsWithoutCallingModel() {
        ChatModel chatModel = mock(ChatModel.class);
        MedicalTriageService service = new MedicalTriageService(chatModel);

        MedicalTriageService.TriageResult result = service.analyze("猫咪抽搐", "PET");

        assertEquals(MedicalTriageService.TriageLevel.CRITICAL, result.getLevel());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
