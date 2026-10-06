package com.example.demo.weather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "weather.api")
public class WeatherApiProperties {

    private String baseUrl;

    private String apiKey;

    private int connectTimeout = 5000;

    private int readTimeout = 10000;

    private int writeTimeout = 5000;
}