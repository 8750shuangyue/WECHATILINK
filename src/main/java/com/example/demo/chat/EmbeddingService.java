package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);
    
    private static final String MODEL_NAME = "text-embedding-v2";
    
    private final DashScopeConfig config;
    
    public EmbeddingService(DashScopeConfig config) {
        this.config = config;
    }
    
    public float[] embed(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", MODEL_NAME);
        
        JSONArray input = new JSONArray();
        input.add(text);
        requestBody.put("input", input);
        
        String jsonRequest = JSON.toJSONString(requestBody);
        
        HttpPost httpPost = new HttpPost(config.getBaseUrl() + "/embeddings");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());
        httpPost.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault();
             org.apache.hc.client5.http.impl.classic.CloseableHttpResponse response = httpClient.execute(httpPost)) {
            
            HttpEntity entity = response.getEntity();
            String responseBody = "";
            if (entity != null) {
                responseBody = EntityUtils.toString(entity, "UTF-8");
            }
            
            logger.debug("Embedding API response: {}", responseBody);
            
            if (response.getCode() != 200) {
                throw new IOException("Embedding API request failed with status: " + response.getCode());
            }
            
            return parseEmbeddingResponse(responseBody);
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Failed to parse embedding response", e);
        }
    }
    
    public List<float[]> embedBatch(List<String> texts) throws IOException {
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(embed(text));
        }
        return embeddings;
    }
    
    private float[] parseEmbeddingResponse(String responseBody) {
        JSONObject responseJson = JSON.parseObject(responseBody);
        
        if (responseJson == null || !responseJson.containsKey("data")) {
            logger.warn("Embedding response does not contain data");
            return new float[0];
        }
        
        Object dataObj = responseJson.get("data");
        if (!(dataObj instanceof JSONArray)) {
            logger.warn("Embedding data is not an array");
            return new float[0];
        }
        
        JSONArray dataArray = (JSONArray) dataObj;
        if (dataArray.isEmpty()) {
            logger.warn("Embedding data array is empty");
            return new float[0];
        }
        
        Object embeddingObj = dataArray.getJSONObject(0).get("embedding");
        if (!(embeddingObj instanceof JSONArray)) {
            logger.warn("Embedding is not an array");
            return new float[0];
        }
        
        JSONArray embeddingArray = (JSONArray) embeddingObj;
        float[] embedding = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            embedding[i] = embeddingArray.getFloatValue(i);
        }
        
        return embedding;
    }
}