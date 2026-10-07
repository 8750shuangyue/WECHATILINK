package com.example.demo.care;

import com.example.demo.aicare.Result;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.repository.mysql.LegacyCareRecordRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.vision.VisionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CareControllerMemoryContractTest {

    @Test
    void qaReusesSessionConversationAndUsesMemoryAwareChat() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chatWithMemory(
                eq("user-a"),
                eq("conversation-a"),
                eq("How often should I water it?"),
                anyString()
        )).thenReturn("Water when the topsoil is dry.");
        CareController controller = createController(llmService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        session.setAttribute("conversationId", "conversation-a");

        Result<Map<String, Object>> result = controller.qa(
                Map.of("question", "How often should I water it?"),
                session
        );

        assertEquals(200, result.getCode());
        assertEquals("Water when the topsoil is dry.", result.getData().get("reply"));
        assertEquals("conversation-a", session.getAttribute("conversationId"));
        verify(llmService).chatWithMemory(
                "user-a",
                "conversation-a",
                "How often should I water it?",
                "你是一位专业的植物和宠物护理专家，请提供详细、科学的护理建议。"
        );
    }

    @Test
    void qaCreatesConversationWhenSessionHasNone() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chatWithMemory(
                eq("user-a"),
                anyString(),
                eq("Can this plant be kept indoors?"),
                anyString()
        )).thenReturn("Yes, with enough light.");
        CareController controller = createController(llmService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");

        controller.qa(Map.of("question", "Can this plant be kept indoors?"), session);

        String conversationId = (String) session.getAttribute("conversationId");
        assertNotNull(conversationId);
        verify(llmService).chatWithMemory(
                "user-a",
                conversationId,
                "Can this plant be kept indoors?",
                "你是一位专业的植物和宠物护理专家，请提供详细、科学的护理建议。"
        );
    }

    private CareController createController(LlmService llmService) {
        return new CareController(
                mock(VisionService.class),
                llmService,
                mock(PlantProfileRepository.class),
                mock(PetProfileRepository.class),
                mock(LegacyCareRecordRepository.class)
        );
    }
}
