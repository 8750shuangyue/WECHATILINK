package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.utils.JsonUtils;
import com.example.demo.weather.tool.WeatherTool;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    private final DashScopeConfig config;
    private final ChatMemoryService chatMemoryService;
    private final CloseableHttpClient httpClient;
    
    @Autowired
    @Lazy
    private VectorStoreService vectorStoreService;

    @Autowired
    @Lazy
    private WeatherTool weatherTool;

    public LlmService(DashScopeConfig config, ChatMemoryService chatMemoryService) {
        this.config = config;
        this.chatMemoryService = chatMemoryService;
        this.httpClient = HttpClients.createDefault();
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
        
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
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

    public String chatWithMemory(String conversationId, String userMessage) throws IOException {
        return chatWithMemory(conversationId, userMessage, null);
    }

    public String chatWithMemory(String conversationId, String userMessage, String systemPrompt) throws IOException {
        logger.info("Chat with memory, conversationId: {}, userMessage: {}", conversationId, userMessage);
        
        List<ChatMessage> promptMessages = chatMemoryService.buildPromptMessages(conversationId, systemPrompt, userMessage);
        
        String ragContext = retrieveRagContext(userMessage, conversationId);
        if (ragContext != null && !ragContext.isEmpty()) {
            String ragSystemMessage = "参考以下历史对话信息，帮助回答用户当前问题：\n\n" + ragContext;
            if (systemPrompt == null) {
                systemPrompt = ragSystemMessage;
            } else {
                systemPrompt = systemPrompt + "\n\n" + ragSystemMessage;
            }
            logger.info("RAG context retrieved, length: {} chars", ragContext.length());
        }
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ChatMessage systemMsg = new ChatMessage("system", systemPrompt);
            if (promptMessages.isEmpty() || !"system".equals(promptMessages.get(0).getRole())) {
                promptMessages.add(0, systemMsg);
            } else {
                promptMessages.set(0, systemMsg);
            }
        }
        
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
        
        if (weatherTool != null && WeatherTool.matchesIntent(userMessage)) {
            String city = WeatherTool.extractCity(userMessage);
            if (city != null) {
                JSONObject weatherParams = new JSONObject();
                weatherParams.put("city", city);
                String weatherData = weatherTool.execute(weatherParams).getData();
                String toolInfo = "你可以使用以下工具获取实时数据：\n" +
                    "工具名称: getWeather\n" +
                    "功能描述: 查询指定城市的天气信息\n" +
                    "已获取到天气数据：\n" + weatherData;
                
                JSONObject toolMessage = new JSONObject();
                toolMessage.put("role", "system");
                toolMessage.put("content", toolInfo);
                messages.add(toolMessage);
                
                logger.info("Weather tool injected into prompt, city: {}", city);
            }
        }
        
        logger.debug("Full request with history: {}", JSON.toJSONString(requestBody));
        
        String reply = executeChatRequest(requestBody);
        chatMemoryService.saveMessagePair(conversationId, userMessage, reply);
        
        asyncSaveVector(conversationId, userMessage, reply);
        
        return reply;
    }
    
    private String retrieveRagContext(String query, String conversationId) {
        try {
            if (vectorStoreService == null) {
                return null;
            }
            List<String> similarMessages = vectorStoreService.searchSimilar(query, conversationId);
            if (similarMessages.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < similarMessages.size(); i++) {
                sb.append("相关对话 ").append(i + 1).append(":\n");
                sb.append(similarMessages.get(i)).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            logger.warn("Failed to retrieve RAG context: {}", e.getMessage());
            return null;
        }
    }
    
    @Async
    public void asyncSaveVector(String conversationId, String userMessage, String assistantReply) {
        try {
            if (vectorStoreService != null) {
                vectorStoreService.saveMessage(conversationId, userMessage, assistantReply);
            }
        } catch (Exception e) {
            logger.error("Failed to async save vector", e);
        }
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