package com.example.demo.ai;

import com.example.demo.care.service.CareRecordService;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.care.service.MedicalTriageService;
import com.example.demo.care.service.SpringAiCareWorkflowService;
import com.example.demo.chat.LlmService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class AiControllerSessionTest {

    @Test
    void reusesConversationWithinSessionAndIsolatesDifferentSessions() throws Exception {
        LlmService llmService = mock(LlmService.class);
        AiController controller = new AiController(
                mock(SpringAiChatService.class),
                mock(ToolCallingService.class),
                mock(SpringAiCareWorkflowService.class),
                mock(CareRecordService.class),
                mock(CareReminderService.class),
                mock(MedicalTriageService.class),
                llmService
        );
        MockHttpSession firstSession = new MockHttpSession();
        firstSession.setAttribute("user", "user-a");
        MockHttpSession secondSession = new MockHttpSession();
        secondSession.setAttribute("user", "user-b");

        controller.chatStream(Map.of("message", "first"), firstSession);
        controller.chatStream(Map.of("message", "second"), firstSession);
        controller.chatStream(Map.of("message", "third"), secondSession);

        ArgumentCaptor<String> userIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> conversationIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(llmService, timeout(2_000).times(3)).chatStream(
                userIds.capture(),
                conversationIds.capture(),
                messages.capture(),
                isNull(),
                any(Consumer.class),
                any(Runnable.class)
        );

        Map<String, String> conversationByMessage = zipToMap(
                messages.getAllValues(),
                conversationIds.getAllValues()
        );
        Map<String, String> userByMessage = zipToMap(
                messages.getAllValues(),
                userIds.getAllValues()
        );
        assertEquals(conversationByMessage.get("first"), conversationByMessage.get("second"));
        assertNotEquals(conversationByMessage.get("first"), conversationByMessage.get("third"));
        assertEquals("user-a", userByMessage.get("first"));
        assertEquals("user-a", userByMessage.get("second"));
        assertEquals("user-b", userByMessage.get("third"));
    }

    private Map<String, String> zipToMap(List<String> keys, List<String> values) {
        return keys.stream().collect(Collectors.toMap(
                key -> key,
                key -> values.get(keys.indexOf(key))
        ));
    }
}
