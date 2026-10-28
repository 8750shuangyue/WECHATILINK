package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.utils.JsonUtils;
import com.example.demo.weather.tool.WeatherTool;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    private final DashScopeConfig config;
    private final ChatMemoryService chatMemoryService;

    @Value("${app.http.llm.connect-timeout-ms:5000}")
    private long connectTimeoutMs = 5000;

    @Value("${app.http.llm.connection-request-timeout-ms:2000}")
    private long connectionRequestTimeoutMs = 2000;

    @Value("${app.http.llm.response-timeout-ms:90000}")
    private long responseTimeoutMs = 90_000;

    private volatile CloseableHttpClient httpClient;

    @Autowired
    @Lazy
    private RagContextService ragContextService;

    @Autowired
    @Lazy
    private WeatherTool weatherTool;

    /**
     * 内联引用的全局开关。关闭后即使调用方开启 includeCitations，
     * 也不追加引用指令、不改变上下文格式，便于线上快速回滚。
     */
    @Value("${chat.citations.enabled:true}")
    private boolean citationsEnabled = true;

    public LlmService(DashScopeConfig config, ChatMemoryService chatMemoryService) {
        this.config = config;
        this.chatMemoryService = chatMemoryService;
    }

    private CloseableHttpClient client() {
        CloseableHttpClient current = httpClient;
        if (current == null) {
            synchronized (this) {
                if (httpClient == null) {
                    RequestConfig requestConfig = RequestConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(Math.max(1, connectTimeoutMs)))
                            .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.max(1, connectionRequestTimeoutMs)))
                            .setResponseTimeout(Timeout.ofMilliseconds(Math.max(1, responseTimeoutMs)))
                            .build();
                    httpClient = HttpClients.custom()
                            .setDefaultRequestConfig(requestConfig)
                            .build();
                }
                current = httpClient;
            }
        }
        return current;
    }

    @PreDestroy
    public void closeClient() {
        CloseableHttpClient current = httpClient;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                logger.debug("Failed to close LLM http client", e);
            }
        }
    }

    public String chat(String userMessage) throws IOException {
        return chat(userMessage, null);
    }

    public String chat(String userMessage, String systemPrompt) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());
        
        JSONArray messages = new JSONArray();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }
        
        JSONObject userMessageObj = new JSONObject();
        userMessageObj.put("role", "user");
        userMessageObj.put("content", userMessage);
        
        messages.add(userMessageObj);
        requestBody.put("messages", messages);
        
        return executeChatRequest(requestBody);
    }
    
    private String executeChatRequest(JSONObject requestBody) throws IOException {
        JSONObject response = executeChatRequestWithResponse(requestBody);
        return parseResponse(JSON.toJSONString(response));
    }

    public JSONObject executeChatRequestWithResponse(JSONObject requestBody) throws IOException {
        String jsonRequest = JSON.toJSONString(requestBody);
        
        HttpPost httpPost = new HttpPost(config.getBaseUrl() + "/chat/completions");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());
        httpPost.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));
        
        try (CloseableHttpResponse response = client().execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            String responseBody = "";
            if (entity != null) {
                responseBody = EntityUtils.toString(entity, "UTF-8");
            }
            
            logger.info("LLM API response status: {}, body: {}", response.getCode(), responseBody);
            
            if (response.getCode() != 200) {
                throw new IOException("LLM API request failed with status: " + response.getCode() + ", body: " + responseBody);
            }
            
            return JSON.parseObject(responseBody);
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Failed to parse LLM response", e);
        }
    }

    public JSONObject chatWithTools(JSONArray messages, JSONArray tools) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", messages);
        
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");
        }
        
        logger.debug("Chat with tools request, tools count: {}", tools != null ? tools.size() : 0);
        
        return executeChatRequestWithResponse(requestBody);
    }

    /**
     * 流式对话：把 DeepSeek 的增量 token 逐个回调给调用方。
     */
    public void chatStream(String userMessage, String systemPrompt,
                           Consumer<String> onToken, Runnable onDone) throws Exception {
        chatStream(null, null, userMessage, systemPrompt, ignored -> {
        }, onToken, onDone);
    }

    public void chatStream(String userId, String conversationId, String userMessage, String systemPrompt,
                           Consumer<String> onToken, Runnable onDone) throws Exception {
        chatStream(userId, conversationId, userMessage, systemPrompt, ignored -> {
        }, onToken, onDone);
    }

    public void chatStream(String userId, String conversationId, String userMessage, String systemPrompt,
                           Consumer<String> onRetrievalTrace,
                           Consumer<String> onToken, Runnable onDone) throws Exception {
        chatStream(userId, conversationId, userMessage, systemPrompt,
                onRetrievalTrace, ignored -> {
                }, onToken, onDone);
    }

    public void chatStream(String userId, String conversationId, String userMessage, String systemPrompt,
                           Consumer<String> onRetrievalTrace,
                           Consumer<List<RagContextService.SourceRef>> onSources,
                           Consumer<String> onToken, Runnable onDone) throws Exception {
        chatStream(userId, conversationId, userMessage, systemPrompt,
                onRetrievalTrace, onSources, onToken, onDone, false);
    }

    /**
     * @param includeCitations true 时按来源编号构建上下文，并要求模型在正文中标注 [n]。
     */
    public void chatStream(String userId, String conversationId, String userMessage, String systemPrompt,
                           Consumer<String> onRetrievalTrace,
                           Consumer<List<RagContextService.SourceRef>> onSources,
                           Consumer<String> onToken, Runnable onDone,
                           boolean includeCitations) throws Exception {
        chatStream(userId, conversationId, userMessage, systemPrompt,
                onRetrievalTrace, onSources, ignored -> {
                }, onToken, onDone, includeCitations);
    }

    /**
     * @param onRetrievalStats 检索/重排/压缩统计回调，先于首个 token；不需要时传 null。
     */
    public void chatStream(String userId, String conversationId, String userMessage, String systemPrompt,
                           Consumer<String> onRetrievalTrace,
                           Consumer<List<RagContextService.SourceRef>> onSources,
                           Consumer<RetrievalTraceStats> onRetrievalStats,
                           Consumer<String> onToken, Runnable onDone,
                           boolean includeCitations) throws Exception {
        boolean citationEnabled = includeCitations && citationsEnabled;
        String effectivePrompt = systemPrompt;

        // 普通流式对话没有工具调用能力，天气这类强实时需求需要提前取数注入，
        // 否则模型只能凭系统提示拒绝回答。
        if (weatherTool != null && WeatherTool.matchesIntent(userMessage)) {
            String city = WeatherTool.extractCity(userMessage);
            if (city != null) {
                JSONObject weatherParams = new JSONObject();
                weatherParams.put("city", city);
                String weatherData = weatherTool.execute(weatherParams).getData();
                String weatherSystem = "用户正在查询天气，以下是已获取到的「" + city + "」实时天气数据，"
                        + "请直接基于这些真实数据回答用户，不要编造、不要提示开启联网搜索：\n" + weatherData;
                effectivePrompt = (effectivePrompt == null || effectivePrompt.isEmpty())
                        ? weatherSystem
                        : effectivePrompt + "\n\n" + weatherSystem;
                logger.info("Weather data injected into chatStream, city: {}", city);
            }
        }

        JSONArray messages = new JSONArray();
        List<RagContextService.SourceRef> retrievedSources = List.of();
        String retrievalTraceId = null;
        if (conversationId != null && !conversationId.isBlank()) {
            RetrievalResult retrieval = retrieveRagContext(
                    userId, userMessage, conversationId, onRetrievalTrace, onSources,
                    onRetrievalStats, citationEnabled);
            String ragContext = retrieval.context();
            retrievedSources = retrieval.sources();
            retrievalTraceId = retrieval.traceId();
            if (citationEnabled) {
                ragContext = CitationPrompt.appendTo(ragContext, retrievedSources.size());
            }
            List<ChatMessage> promptMessages = chatMemoryService.buildPromptMessages(
                    userId,
                    conversationId,
                    effectivePrompt,
                    ragContext,
                    userMessage
            );
            for (ChatMessage message : promptMessages) {
                JSONObject messageObj = new JSONObject();
                messageObj.put("role", message.getRole());
                messageObj.put("content", message.getContent());
                messages.add(messageObj);
            }
        } else {
            if (onSources != null) {
                onSources.accept(List.of());
            }
            if (effectivePrompt != null && !effectivePrompt.isEmpty()) {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", effectivePrompt);
                messages.add(sys);
            }
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userMessage);
            messages.add(user);
        }

        final List<RagContextService.SourceRef> savedSources = retrievedSources;
        final String savedTraceId = retrievalTraceId;

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", messages);
        requestBody.put("stream", true);

        HttpPost httpPost = new HttpPost(config.getBaseUrl() + "/chat/completions");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());
        httpPost.setEntity(new StringEntity(JSON.toJSONString(requestBody), ContentType.APPLICATION_JSON));

        StringBuilder fullReply = new StringBuilder();
        client().execute(httpPost, response -> {
            if (response.getCode() != 200) {
                throw new IOException("Streaming LLM request failed with status: " + response.getCode());
            }

            boolean completed = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        completed = true;
                        break;
                    }
                    try {
                        JSONObject json = JSON.parseObject(data);
                        JSONArray choices = json.getJSONArray("choices");
                        if (choices == null || choices.isEmpty()) {
                            continue;
                        }
                        JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                        String content = delta != null ? delta.getString("content") : null;
                        if (content != null && !content.isEmpty()) {
                            fullReply.append(content);
                            onToken.accept(content);
                        }
                    } catch (Exception ignore) {
                        // 跳过无法解析的增量
                    }
                }
            }

            if (!completed) {
                throw new IOException("Streaming LLM response ended before [DONE]");
            }

            if (conversationId != null && !conversationId.isBlank() && fullReply.length() > 0) {
                try {
                    if (savedSources == null || savedSources.isEmpty()) {
                        chatMemoryService.saveMessagePair(userId, conversationId, userMessage,
                                fullReply.toString());
                    } else {
                        chatMemoryService.saveMessagePair(userId, conversationId, userMessage,
                                fullReply.toString(), savedSources, savedTraceId);
                    }
                } catch (Exception e) {
                    logger.error("Failed to save streaming conversation memory, conversationId: {}",
                            conversationId, e);
                }
            }
            return null;
        });
        onDone.run();
    }

    public String chatWithMemory(String userId, String conversationId, String userMessage, String systemPrompt) throws IOException {
        return chatWithMemory(userId, conversationId, userMessage, systemPrompt, ignored -> {
        });
    }

    public String chatWithMemory(String userId, String conversationId, String userMessage,
                                 String systemPrompt,
                                 Consumer<List<RagContextService.SourceRef>> onSources) throws IOException {
        return chatWithMemory(userId, conversationId, userMessage, systemPrompt, onSources, false);
    }

    public String chatWithMemory(String userId, String conversationId, String userMessage,
                                 String systemPrompt,
                                 Consumer<List<RagContextService.SourceRef>> onSources,
                                 boolean includeCitations) throws IOException {
        boolean citationEnabled = includeCitations && citationsEnabled;
        logger.info("Chat with memory, userId: {}, conversationId: {}, userMessage: {}", userId, conversationId, userMessage);

        String effectivePrompt = systemPrompt;
        if (weatherTool != null && WeatherTool.matchesIntent(userMessage)) {
            String city = WeatherTool.extractCity(userMessage);
            if (city != null) {
                JSONObject weatherParams = new JSONObject();
                weatherParams.put("city", city);
                String weatherData = weatherTool.execute(weatherParams).getData();
                String weatherSystem = "你可以使用以下工具获取实时数据：\n" +
                        "工具名称: getWeather\n" +
                        "功能描述: 查询指定城市的天气信息\n" +
                        "已获取到天气数据：\n" + weatherData;
                effectivePrompt = (effectivePrompt == null || effectivePrompt.isEmpty())
                        ? weatherSystem
                        : effectivePrompt + "\n\n" + weatherSystem;
                logger.info("Weather tool injected into prompt, city: {}", city);
            }
        }

        RetrievalResult retrieval = retrieveRagContext(userId, userMessage, conversationId,
                ignored -> {
                }, onSources, null, citationEnabled);
        String ragContext = retrieval.context();
        if (citationEnabled) {
            ragContext = CitationPrompt.appendTo(ragContext, retrieval.sources().size());
        }

        List<ChatMessage> promptMessages = chatMemoryService.buildPromptMessages(
                userId, conversationId, effectivePrompt, ragContext, userMessage);
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());
        
        JSONArray messages = new JSONArray();
        for (ChatMessage msg : promptMessages) {
            JSONObject messageObj = new JSONObject();
            messageObj.put("role", msg.getRole());
            messageObj.put("content", msg.getContent());
            messages.add(messageObj);
        }
        requestBody.put("messages", messages);
        
        logger.debug("Full request with history: {}", JSON.toJSONString(requestBody));
        
        String reply = executeChatRequest(requestBody);
        if (retrieval.sources().isEmpty()) {
            chatMemoryService.saveMessagePair(userId, conversationId, userMessage, reply);
        } else {
            chatMemoryService.saveMessagePair(userId, conversationId, userMessage, reply,
                    retrieval.sources(), retrieval.traceId());
        }

        return reply;
    }
    
    private RetrievalResult retrieveRagContext(String userId, String query, String conversationId,
                                               Consumer<String> onRetrievalTrace,
                                               Consumer<List<RagContextService.SourceRef>> onSources,
                                               Consumer<RetrievalTraceStats> onRetrievalStats,
                                               boolean citationEnabled) {
        if (ragContextService == null) {
            if (onSources != null) {
                onSources.accept(List.of());
            }
            return new RetrievalResult("", List.of(), null);
        }
        RagContextService.ContextResult contextResult = citationEnabled
                ? ragContextService.buildContextWithTrace(userId, conversationId, query, true)
                : ragContextService.buildContextWithTrace(userId, conversationId, query);
        String ragContext;
        List<RagContextService.SourceRef> sources = List.of();
        String traceId = null;
        if (contextResult == null) {
            // Keep compatibility with older service implementations and test doubles.
            ragContext = ragContextService.buildContext(userId, conversationId, query);
        } else {
            ragContext = contextResult.context();
            sources = contextResult.sources() == null ? List.of() : contextResult.sources();
            traceId = contextResult.traceId();
            if (onRetrievalTrace != null
                    && traceId != null
                    && !traceId.isBlank()) {
                onRetrievalTrace.accept(traceId);
            }
        }
        if (onSources != null) {
            onSources.accept(sources);
        }
        if (onRetrievalStats != null && contextResult != null && contextResult.stats() != null) {
            onRetrievalStats.accept(contextResult.stats());
        }
        if (ragContext == null) {
            ragContext = "";
        }
        if (!ragContext.isEmpty()) {
            logger.info("RAG context retrieved, length: {} chars", ragContext.length());
        }
        return new RetrievalResult(ragContext, sources, traceId);
    }

    private record RetrievalResult(String context,
                                   List<RagContextService.SourceRef> sources,
                                   String traceId) {
    }
    
    private String parseResponse(String responseBody) {
        logger.debug("Parsing LLM response: {}", responseBody);
        
        JSONObject responseJson = JSON.parseObject(responseBody);
        
        if (responseJson == null || !responseJson.containsKey("choices")) {
            logger.warn("LLM response does not contain choices: {}", responseBody);
            return "抱歉，我现在无法回答你的问题。";
        }
        
        Object choices = responseJson.get("choices");
        JSONArray choicesArray;
        
        if (choices instanceof JSONArray) {
            choicesArray = (JSONArray) choices;
        } else if (choices instanceof Object[]) {
            choicesArray = new JSONArray();
            for (Object item : (Object[]) choices) {
                choicesArray.add(item);
            }
        } else {
            logger.warn("LLM choices is not an array: {}", choices);
            return "抱歉，我现在无法回答你的问题。";
        }
        
        if (choicesArray.isEmpty()) {
            logger.warn("LLM choices array is empty");
            return "抱歉，我现在无法回答你的问题。";
        }
        
        Object choiceObj = choicesArray.get(0);
        if (!(choiceObj instanceof JSONObject)) {
            logger.warn("LLM choice is not a JSONObject: {}", choiceObj);
            return "抱歉，我现在无法回答你的问题。";
        }
        
        JSONObject choice = (JSONObject) choiceObj;
        Object messageObj = choice.get("message");
        if (!(messageObj instanceof JSONObject)) {
            logger.warn("LLM message is not a JSONObject: {}", messageObj);
            return "抱歉，我现在无法回答你的问题。";
        }
        
        JSONObject message = (JSONObject) messageObj;
        String content = message.getString("content");
        
        if (content == null) {
            logger.warn("LLM content is null");
            return "抱歉，我现在无法回答你的问题。";
        }
        
        return formatText(content);
    }

    private String formatText(String text) {
        text = JsonUtils.unescapeJson(text);
        text = text.replaceAll("---+", "");
        text = formatPoetry(text);
        return text;
    }

    private String formatPoetry(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        int poemLineCount = 0;
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                sb.append("\n");
                poemLineCount = 0;
                continue;
            }
            
            boolean isTitle = line.matches("《.+》");
            boolean isAuthor = line.contains("作者：") || line.contains("作者:");
            
            if (isTitle || isAuthor) {
                sb.append(line).append("\n\n");
                poemLineCount = 0;
                continue;
            }
            
            boolean isPoemLine = line.matches(".*[，。！？、；：].*") && !line.contains("：") && !line.startsWith("\"");
            
            if (isPoemLine) {
                sb.append(line);
                poemLineCount++;
                if (poemLineCount >= 2) {
                    sb.append("\n\n");
                    poemLineCount = 0;
                } else {
                    sb.append("    ");
                }
            } else {
                sb.append(line).append("\n");
                poemLineCount = 0;
            }
        }
        
        return sb.toString().trim();
    }
}
