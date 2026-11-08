package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.utils.JsonUtils;
import com.example.demo.weather.tool.WeatherTool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    private final OkHttpClient sharedHttpClient;
    private volatile OkHttpClient llmClient;

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

    /** 单测装配：不依赖 Spring 的共享客户端。 */
    public LlmService(DashScopeConfig config, ChatMemoryService chatMemoryService) {
        this(config, chatMemoryService, new OkHttpClient());
    }

    @Autowired
    public LlmService(DashScopeConfig config, ChatMemoryService chatMemoryService,
                      OkHttpClient sharedHttpClient) {
        this.config = config;
        this.chatMemoryService = chatMemoryService;
        this.sharedHttpClient = sharedHttpClient;
    }

    private OkHttpClient client() {
        OkHttpClient current = llmClient;
        if (current == null) {
            synchronized (this) {
                if (llmClient == null) {
                    llmClient = sharedHttpClient.newBuilder()
                            .connectTimeout(Math.max(1, connectTimeoutMs), TimeUnit.MILLISECONDS)
                            .readTimeout(Math.max(1, responseTimeoutMs), TimeUnit.MILLISECONDS)
                            .writeTimeout(Math.max(1, responseTimeoutMs), TimeUnit.MILLISECONDS)
                            .build();
                }
                current = llmClient;
            }
        }
        return current;
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
        
        Request httpRequest = new Request.Builder()
                .url(config.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .post(RequestBody.create(jsonRequest, MediaType.parse("application/json")))
                .build();

        try (Response response = client().newCall(httpRequest).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();

            logger.info("LLM API response status: {}, body: {}", response.code(),
                    com.example.demo.core.LogSanitizer.describe(responseBody));

            if (response.code() != 200) {
                throw new IOException("LLM API request failed with status: " + response.code()
                        + ", bodyLen: " + responseBody.length());
            }

            return JSON.parseObject(responseBody);
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

        Request httpRequest = new Request.Builder()
                .url(config.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .post(RequestBody.create(JSON.toJSONString(requestBody),
                        MediaType.parse("application/json")))
                .build();

        StringBuilder fullReply = new StringBuilder();
        try (Response response = client().newCall(httpRequest).execute()) {
            if (response.code() != 200) {
                throw new IOException("Streaming LLM request failed with status: " + response.code());
            }

            boolean completed = false;
            try (BufferedReader reader = new BufferedReader(response.body().charStream())) {
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
        }
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
        logger.info("Chat with memory, userId: {}, conversationId: {}, userMessage: {}",
                userId, conversationId, com.example.demo.core.LogSanitizer.describe(userMessage));

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
        
        logger.debug("Full request with history: {}",
                com.example.demo.core.LogSanitizer.describe(JSON.toJSONString(requestBody)));
        
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
        logger.debug("Parsing LLM response: {}",
                com.example.demo.core.LogSanitizer.describe(responseBody));
        
        JSONObject responseJson = JSON.parseObject(responseBody);
        
        if (responseJson == null || !responseJson.containsKey("choices")) {
            logger.warn("LLM response does not contain choices: {}",
                    com.example.demo.core.LogSanitizer.describe(responseBody));
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
            logger.warn("LLM message is not a JSONObject: {}",
                    com.example.demo.core.LogSanitizer.describe(String.valueOf(messageObj)));
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
