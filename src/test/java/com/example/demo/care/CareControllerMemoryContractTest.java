package com.example.demo.care;

import com.example.demo.aicare.Result;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.RagContextService;
import com.example.demo.chat.repository.mysql.LegacyCareRecordRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.vision.VisionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
                anyString(),
                any()
        )).thenAnswer(invocation -> {
            Consumer<List<RagContextService.SourceRef>> onSources = invocation.getArgument(4);
            onSources.accept(List.of(
                    new RagContextService.SourceRef("kb_plant_watering", "浇水与积水")));
            return "Water when the topsoil is dry.";
        });
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
        @SuppressWarnings("unchecked")
        List<RagContextService.SourceRef> sources =
                (List<RagContextService.SourceRef>) result.getData().get("sources");
        assertEquals(1, sources.size());
        assertEquals("kb_plant_watering", sources.get(0).sourceId());
        verify(llmService).chatWithMemory(
                eq("user-a"),
                eq("conversation-a"),
                eq("How often should I water it?"),
                eq("你是一位专业的植物和宠物护理专家，请提供详细、科学的护理建议。"),
                any(Consumer.class)
        );
    }

    @Test
    void qaPassesIncludeCitationsToMemoryAwareChat() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chatWithMemory(
                eq("user-a"),
                eq("conversation-a"),
                eq("How often should I water it?"),
                anyString(),
                any(),
                eq(true)
        )).thenReturn("Water when the topsoil is dry.");
        CareController controller = createController(llmService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        session.setAttribute("conversationId", "conversation-a");

        Result<Map<String, Object>> result = controller.qa(
                Map.of("question", "How often should I water it?", "includeCitations", true),
                session
        );

        assertEquals(200, result.getCode());
        assertEquals("Water when the topsoil is dry.", result.getData().get("reply"));
        verify(llmService).chatWithMemory(
                eq("user-a"),
                eq("conversation-a"),
                eq("How often should I water it?"),
                anyString(),
                any(),
                eq(true)
        );
    }

    @Test
    void qaCreatesConversationWhenSessionHasNone() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chatWithMemory(
                eq("user-a"),
                anyString(),
                eq("Can this plant be kept indoors?"),
                anyString(),
                any()
        )).thenReturn("Yes, with enough light.");
        CareController controller = createController(llmService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");

        Result<Map<String, Object>> result =
                controller.qa(Map.of("question", "Can this plant be kept indoors?"), session);

        String conversationId = (String) session.getAttribute("conversationId");
        assertNotNull(conversationId);
        assertTrue(((List<?>) result.getData().get("sources")).isEmpty());
        verify(llmService).chatWithMemory(
                eq("user-a"),
                eq(conversationId),
                eq("Can this plant be kept indoors?"),
                eq("你是一位专业的植物和宠物护理专家，请提供详细、科学的护理建议。"),
                any(Consumer.class)
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
