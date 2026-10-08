package com.example.demo.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagContextServiceTest {

    @Test
    void separatesPublicKnowledgeFromConversationMemory() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadata("question", "user-a", "conversation-a"))
                .thenReturn(List.of(
                        new VectorStoreService.SearchResult(
                                "public-doc",
                                "guide-1",
                                "公共护理知识",
                                0.95,
                                null,
                                VectorStoreService.ORIGIN_PUBLIC_KB
                        ),
                        new VectorStoreService.SearchResult(
                                "conversation-doc",
                                null,
                                "用户: 我的宠物叫豆豆\n助手: 记住了",
                                0.91,
                                null,
                                VectorStoreService.ORIGIN_CONVERSATION
                        )
                ));
        RagContextService service = new RagContextService(vectorStoreService);

        String context = service.buildContext("user-a", "conversation-a", "question");

        assertTrue(context.contains("公共知识库信息 1:\n公共护理知识"));
        assertTrue(context.contains("相关历史对话 1:\n用户: 我的宠物叫豆豆\n助手: 记住了"));
        assertTrue(context.indexOf("公共知识库信息") < context.indexOf("相关历史对话"));
    }

    @Test
    void returnsEmptyContextWhenQueryBlank() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        RagContextService service = new RagContextService(vectorStoreService);

        assertEquals("", service.buildContext("user-a", "conversation-a", "  "));
        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void returnsEmptyContextWhenRetrievalFails() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreService.searchSimilarWithMetadata("question", "user-a", "conversation-a"))
                .thenThrow(new IllegalStateException("vector store unavailable"));
        RagContextService service = new RagContextService(vectorStoreService);

        assertEquals("", service.buildContext("user-a", "conversation-a", "question"));
        verify(vectorStoreService).searchSimilarWithMetadata("question", "user-a", "conversation-a");
    }
}
