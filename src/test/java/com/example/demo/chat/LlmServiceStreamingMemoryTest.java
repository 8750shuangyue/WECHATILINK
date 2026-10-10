package com.example.demo.chat;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmServiceStreamingMemoryTest {

    private HttpServer server;
    private AtomicReference<String> requestBody;
    private AtomicReference<StreamResponse> response;

    @BeforeEach
    void setUp() throws IOException {
        requestBody = new AtomicReference<>();
        response = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            StreamResponse current = response.get();
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(current.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsHistoryAndPersistsFullReplyAfterDone() throws Exception {
        response.set(new StreamResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Doudou\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\" is 5.\"}}]}\n\n"
                        + "data: [DONE]\n\n"
        ));
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        when(memoryService.buildPromptMessages(
                "user-a",
                "conversation-a",
                "Be concise.",
                "",
                "What is my dog's name and age?"
        )).thenReturn(List.of(
                new ChatMessage("system", "Be concise.\n\n【对话摘要】The dog is named Doudou."),
                new ChatMessage("user", "My dog is named Doudou and is 5 years old."),
                new ChatMessage("assistant", "I will remember that."),
                new ChatMessage("user", "What is my dog's name and age?")
        ));
        LlmService service = new LlmService(config(), memoryService);
        ReflectionTestUtils.setField(service, "ragContextService", emptyRagContextService());
        List<String> tokens = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        service.chatStream(
                "user-a",
                "conversation-a",
                "What is my dog's name and age?",
                "Be concise.",
                tokens::add,
                () -> done.set(true)
        );

        JSONObject sent = JSONObject.parseObject(requestBody.get());
        assertEquals(4, sent.getJSONArray("messages").size());
        assertEquals(
                "My dog is named Doudou and is 5 years old.",
                sent.getJSONArray("messages").getJSONObject(1).getString("content")
        );
        assertTrue(
                sent.getJSONArray("messages").getJSONObject(0).getString("content")
                        .contains("【对话摘要】The dog is named Doudou.")
        );
        assertEquals(List.of("Doudou", " is 5."), tokens);
        verify(memoryService).saveMessagePair(
                "user-a",
                "conversation-a",
                "What is my dog's name and age?",
                "Doudou is 5."
        );
        assertTrue(done.get());
    }

    @Test
    void doesNotPersistPartialReplyWhenStreamEndsBeforeDone() throws Exception {
        response.set(new StreamResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Doudou\"}}]}\n\n"
        ));
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        when(memoryService.buildPromptMessages(
                "user-a",
                "conversation-a",
                null,
                "",
                "What is my dog's name?"
        )).thenReturn(List.of(
                new ChatMessage("user", "My dog is named Doudou."),
                new ChatMessage("user", "What is my dog's name?")
        ));
        LlmService service = new LlmService(config(), memoryService);
        ReflectionTestUtils.setField(service, "ragContextService", emptyRagContextService());

        assertThrows(
                IOException.class,
                () -> service.chatStream(
                        "user-a",
                        "conversation-a",
                        "What is my dog's name?",
                        null,
                        ignored -> {
                        },
                        () -> {
                        }
                )
        );

        verify(memoryService, never()).saveMessagePair(
                "user-a",
                "conversation-a",
                "What is my dog's name?",
                "Doudou"
        );
    }

    @Test
    void chatWithMemoryPassesRagIntoPromptBuilderWithoutReplacingSummary() throws Exception {
        response.set(new StreamResponse(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Doudou is 5.\"}}]}"
        ));
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        String ragContext = "相关对话 1:\nDoudou is 5.";
        when(memoryService.buildPromptMessages(
                "user-a",
                "conversation-a",
                null,
                ragContext,
                "How old is Doudou?"
        )).thenReturn(List.of(
                new ChatMessage("system",
                        "参考以下历史对话信息，帮助回答用户当前问题：\n"
                                + ragContext
                                + "\n\n【对话摘要】Doudou is 5 years old."),
                new ChatMessage("user", "My dog Doudou is 5 years old."),
                new ChatMessage("user", "How old is Doudou?")
        ));
        RagContextService ragContextService = mock(RagContextService.class);
        when(ragContextService.buildContext("user-a", "conversation-a", "How old is Doudou?"))
                .thenReturn(ragContext);
        LlmService service = new LlmService(config(), memoryService);
        ReflectionTestUtils.setField(service, "ragContextService", ragContextService);

        String reply = service.chatWithMemory(
                "user-a",
                "conversation-a",
                "How old is Doudou?",
                null
        );

        assertEquals("Doudou is 5.", reply);
        JSONObject sent = JSONObject.parseObject(requestBody.get());
        assertEquals(3, sent.getJSONArray("messages").size());
        String sentSystem = sent.getJSONArray("messages").getJSONObject(0).getString("content");
        assertTrue(sentSystem.contains(ragContext));
        assertTrue(sentSystem.contains("【对话摘要】Doudou is 5 years old."));
        assertEquals(
                "How old is Doudou?",
                sent.getJSONArray("messages").getJSONObject(2).getString("content")
        );
        verify(memoryService).buildPromptMessages(
                "user-a",
                "conversation-a",
                null,
                ragContext,
                "How old is Doudou?"
        );
        verify(memoryService).saveMessagePair(
                "user-a",
                "conversation-a",
                "How old is Doudou?",
                "Doudou is 5."
        );
    }

    @Test
    void chatStreamPassesRagIntoPromptBuilderAndKeepsSummary() throws Exception {
        response.set(new StreamResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Doudou\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\" is 5.\"}}]}\n\n"
                        + "data: [DONE]\n\n"
        ));
        String ragContext = "公共知识库信息 1:\n狗的正常年龄判断需要结合体型。";
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        when(memoryService.buildPromptMessages(
                "user-a",
                "conversation-a",
                null,
                ragContext,
                "How old is Doudou?"
        )).thenReturn(List.of(
                new ChatMessage("system",
                        "参考以下检索结果，帮助回答用户当前问题：\n"
                                + ragContext
                                + "\n\n【对话摘要】Doudou is 5 years old."),
                new ChatMessage("user", "How old is Doudou?")
        ));
        RagContextService ragContextService = mock(RagContextService.class);
        when(ragContextService.buildContext("user-a", "conversation-a", "How old is Doudou?"))
                .thenReturn(ragContext);
        LlmService service = new LlmService(config(), memoryService);
        ReflectionTestUtils.setField(service, "ragContextService", ragContextService);

        service.chatStream(
                "user-a",
                "conversation-a",
                "How old is Doudou?",
                null,
                ignored -> {
                },
                () -> {
                }
        );

        JSONObject sent = JSONObject.parseObject(requestBody.get());
        String sentSystem = sent.getJSONArray("messages").getJSONObject(0).getString("content");
        assertTrue(sentSystem.contains(ragContext));
        assertTrue(sentSystem.contains("【对话摘要】Doudou is 5 years old."));
        verify(memoryService).buildPromptMessages(
                "user-a",
                "conversation-a",
                null,
                ragContext,
                "How old is Doudou?"
        );
    }

    @Test
    void emitsRetrievalTraceBeforeTheFirstToken() throws Exception {
        response.set(new StreamResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Doudou\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\" is 5.\"}}]}\n\n"
                        + "data: [DONE]\n\n"
        ));
        String ragContext = "公共知识库信息 1:\n狗的正常年龄判断需要结合体型。";
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        when(memoryService.buildPromptMessages(
                "user-a",
                "conversation-a",
                null,
                ragContext,
                "How old is Doudou?"
        )).thenReturn(List.of(new ChatMessage("user", "How old is Doudou?")));
        RagContextService ragContextService = mock(RagContextService.class);
        when(ragContextService.buildContextWithTrace(
                "user-a", "conversation-a", "How old is Doudou?"))
                .thenReturn(new RagContextService.ContextResult(ragContext, "trace-1"));
        LlmService service = new LlmService(config(), memoryService);
        ReflectionTestUtils.setField(service, "ragContextService", ragContextService);
        List<String> events = new ArrayList<>();

        service.chatStream(
                "user-a",
                "conversation-a",
                "How old is Doudou?",
                null,
                traceId -> events.add("trace:" + traceId),
                events::add,
                () -> events.add("done")
        );

        assertEquals(List.of("trace:trace-1", "Doudou", " is 5.", "done"), events);
    }

    private RagContextService emptyRagContextService() {
        RagContextService ragContextService = mock(RagContextService.class);
        when(ragContextService.buildContext(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("");
        return ragContextService;
    }

    private DashScopeConfig config() {
        DashScopeConfig config = new DashScopeConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setApiKey("test-key");
        config.setModel("test-model");
        return config;
    }

    private record StreamResponse(int status, String body) {
    }
}
