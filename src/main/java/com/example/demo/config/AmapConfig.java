package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "amap")
public class AmapConfig {

    private String apiKey;
    private String baseUrl = "https://restapi.amap.com/v3";
    private String webNavBaseUrl = "https://uri.amap.com";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWebNavBaseUrl() {
        return webNavBaseUrl;
    }

    public void setWebNavBaseUrl(String webNavBaseUrl) {
        this.webNavBaseUrl = webNavBaseUrl;
    }
}